using Npgsql;
using System.Text.Json.Nodes;

namespace SystemDesignLab;

// No stock or idempotency state is kept in the API process.
public sealed class Commerce : IDisposable
{
    private readonly NpgsqlDataSource? source;
    public bool Configured => source is not null;
    public Commerce()
    {
        var host = Environment.GetEnvironmentVariable("LAB_COMMERCE_HOST");
        if (string.IsNullOrWhiteSpace(host)) return;
        var passwordFile = Environment.GetEnvironmentVariable("LAB_COMMERCE_PASSWORD_FILE")
            ?? throw new InvalidOperationException("Commerce password file is required");
        var options = new NpgsqlConnectionStringBuilder
        {
            Host = host, Database = "atlas_commerce", Username = "atlas_app",
            Password = File.ReadAllText(passwordFile).Trim(), Timeout = 3,
            CommandTimeout = 5, MaxPoolSize = 16, IncludeErrorDetail = false
        };
        source = NpgsqlDataSource.Create(options.ConnectionString);
    }
    private async Task<JsonObject> Query(string sql, params object[] parameters)
    {
        if (source is null) throw new RuleError(503, "commerce_not_configured");
        try
        {
            await using var command = source.CreateCommand(sql);
            foreach (var value in parameters) command.Parameters.AddWithValue(value);
            var json = (string)(await command.ExecuteScalarAsync() ?? throw new InvalidDataException("Empty commerce result"));
            return JsonNode.Parse(json)!.AsObject();
        }
        catch (PostgresException e) when (e.SqlState == "P0002" && e.MessageText == "product_not_found")
        { throw new RuleError(404, "product_not_found"); }
        catch (PostgresException e) when (e.SqlState == "P0001" && e.MessageText is "out_of_stock" or "idempotency_conflict" or "invalid_key" or "invalid_product" or "invalid_quantity")
        { throw new RuleError(e.MessageText.StartsWith("invalid_", StringComparison.Ordinal) ? 422 : 409, e.MessageText); }
        catch (NpgsqlException) { throw new RuleError(503, "commerce_unavailable"); }
        catch (TimeoutException) { throw new RuleError(503, "commerce_unavailable"); }
    }
    public async Task<JsonObject> Health()
    {
        var health = await Query("SELECT jsonb_build_object('schemaVersion', version, 'bootstrapSha256', bootstrap_sha256) FROM shop.schema_version WHERE version=1");
        health["status"] = "up"; health["implementation"] = "csharp";
        health["instance"] = Environment.GetEnvironmentVariable("LAB_INSTANCE") ?? Environment.MachineName;
        health["revision"] = Environment.GetEnvironmentVariable("LAB_REVISION") ?? "development";
        health["peerUrl"] = Environment.GetEnvironmentVariable("LAB_COMMERCE_PEER_URL") ?? "";
        return health;
    }
    public Task<JsonObject> State() => Query("""
        SELECT jsonb_build_object(
            'products', (SELECT coalesce(jsonb_agg(jsonb_build_object('id',id,'name',name,'priceCents',price_cents,'stock',stock) ORDER BY id),'[]'::jsonb) FROM shop.products),
            'orders', (SELECT coalesce(jsonb_agg(shop.receipt(o) ORDER BY placed_at,id),'[]'::jsonb) FROM shop.orders o),
            'orderCount', (SELECT count(*) FROM shop.orders),
            'unitsSold', (SELECT coalesce(sum(quantity),0) FROM shop.orders))
        """);
    public Task<JsonObject> Order(JsonObject request)
    {
        var key = Text(request, "key"); var product = Text(request, "product");
        if (request["quantity"] is not JsonValue number || !number.TryGetValue<int>(out var quantity) || quantity is < 1 or > 100)
            throw new RuleError(422, "invalid_quantity");
        return Query("SELECT shop.place_order($1,$2,$3)", key, product, quantity);
    }
    private static string Text(JsonObject request, string field)
    {
        if (request[field] is not JsonValue value || !value.TryGetValue<string>(out var text) || string.IsNullOrWhiteSpace(text) || text.Length > 100 || text.Any(char.IsControl))
            throw new RuleError(422, "invalid_" + field);
        return text.Trim();
    }
    public void Dispose() => source?.Dispose();
}
