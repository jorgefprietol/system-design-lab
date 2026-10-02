using System.Text.Json.Nodes;
using System.Security.Cryptography;

namespace SystemDesignLab;

public sealed class RuleError(int status, string code) : Exception(code)
{
    public int Status { get; } = status;
}

// All commands and projections share one lock: deterministic, single-node transactions.
public sealed class Lab : IDisposable
{
    private readonly object gate = new();
    private readonly string path;
    private readonly FileStream lease;
    private readonly List<JsonObject> events = [];
    private readonly Dictionary<string, Dictionary<string, JsonObject>> tables = new();
    private readonly Dictionary<string, (string Url, DateTime Until)> cache = new();
    private long cacheHits, cacheMisses;
    public Lab(string directory)
    {
        Directory.CreateDirectory(directory);
        path = Path.Combine(directory, "events.json");
        lease = new FileStream(Path.Combine(directory, "writer.lock"), FileMode.OpenOrCreate, FileAccess.ReadWrite, FileShare.None);
        try
        {
            foreach (var name in new[] { "links", "messages", "products", "orders", "posts", "follows", "drivers", "rides", "jobs", "files" }) tables[name] = new();
            Table("products")["book"] = Obj("id", "book", "name", "Arquitectura de sistemas", "priceCents", 2500, "stock", 5);
            Table("products")["keyboard"] = Obj("id", "keyboard", "name", "Teclado mecánico", "priceCents", 6500, "stock", 8);
            if (File.Exists(path))
            {
                var saved = JsonNode.Parse(File.ReadAllText(path))!.AsArray();
                foreach (var item in saved)
                {
                    var e = item!.AsObject();
                    if (e["sequence"]!.GetValue<long>() != events.Count + 1) throw new InvalidDataException("Invalid event sequence");
                    Apply(e); events.Add(e);
                }
            }
        }
        catch { lease.Dispose(); throw; }
    }
    private Dictionary<string, JsonObject> Table(string name) => tables[name];
    public static JsonObject Obj(params object[] values)
    {
        var o = new JsonObject();
        for (var i = 0; i < values.Length; i += 2) o[(string)values[i]] = System.Text.Json.JsonSerializer.SerializeToNode(values[i + 1]);
        return o;
    }
    private static string Text(JsonObject o, string key, int max = 100)
    {
        if (o[key] is not JsonValue v || !v.TryGetValue<string>(out var s) || string.IsNullOrWhiteSpace(s) || s.Length > max)
            throw new RuleError(422, "invalid_" + key);
        return s.Trim();
    }
    private static int Number(JsonObject o, string key, int min, int max)
    {
        if (o[key] is not JsonValue v || !v.TryGetValue<int>(out var n) || n < min || n > max) throw new RuleError(422, "invalid_" + key);
        return n;
    }
    private static double Coordinate(JsonObject o, string key, double limit)
    {
        if (o[key] is not JsonValue v || !v.TryGetValue<double>(out var n) || !double.IsFinite(n) || Math.Abs(n) > limit) throw new RuleError(422, "invalid_" + key);
        return n;
    }
    private JsonObject Find(string table, string id) => Table(table).GetValueOrDefault(id) ?? throw new RuleError(404, table + "_not_found");
    private static string Id() => Guid.NewGuid().ToString("N");
    private static string S(JsonObject o, string key) => o[key]!.GetValue<string>();
    private static int I(JsonObject o, string key) => o[key]!.GetValue<int>();
    private static JsonObject Copy(JsonObject o) => o.DeepClone().AsObject();
    private JsonObject Commit(string type, JsonObject data)
    {
        var e = Obj("sequence", events.Count + 1, "time", DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(), "type", type, "data", data);
        var next = new JsonArray(events.Select(x => x.DeepClone()).Append(e.DeepClone()).ToArray());
        var tmp = path + ".tmp";
        using (var stream = new FileStream(tmp, FileMode.Create, FileAccess.Write, FileShare.None))
        {
            using var writer = new System.Text.Json.Utf8JsonWriter(stream);
            next.WriteTo(writer); writer.Flush(); stream.Flush(true);
        }
        File.Move(tmp, path, true);
        Apply(e); events.Add(e);
        return Copy(data);
    }
    private void Apply(JsonObject e)
    {
        var d = Copy(e["data"]!.AsObject());
        var type = S(e, "type");
        var table = type switch
        {
            "LinkCreated" => "links", "MessageSent" => "messages", "OrderPlaced" => "orders", "PostPublished" => "posts",
            "FollowAdded" => "follows", "DriverLocated" => "drivers", "RideAssigned" or "RideCompleted" => "rides",
            "CrawlQueued" or "CrawlCompleted" => "jobs", "FileVersionAdded" => "files", _ => throw new InvalidDataException("Unknown event")
        };
        Table(table)[S(d, "id")] = d;
        if (type == "OrderPlaced") { var product = Find("products", S(d, "product")); product["stock"] = I(product, "stock") - I(d, "quantity"); }
        if (type is "RideAssigned" or "RideCompleted") Find("drivers", S(d, "driver"))["available"] = type == "RideCompleted";
    }
    public JsonObject Command(string route, JsonObject request)
    {
        lock (gate)
        {
            switch (route)
            {
                case "links":
                    var url = Text(request, "url", 2048);
                    if (!Uri.TryCreate(url, UriKind.Absolute, out var uri) || uri.Scheme is not ("http" or "https") || string.IsNullOrEmpty(uri.Host) || url.Any(char.IsControl)) throw new RuleError(422, "invalid_url");
                    string code; do { code = Id()[..12]; } while (Table("links").ContainsKey(code));
                    return Commit("LinkCreated", Obj("id", code, "url", url));
                case "messages":
                    return Commit("MessageSent", Obj("id", Id(), "room", Text(request, "room"), "user", Text(request, "user"), "text", Text(request, "text", 2000)));
                case "orders":
                    var key = Text(request, "key"); var productId = Text(request, "product"); var quantity = Number(request, "quantity", 1, 100);
                    var old = Table("orders").Values.FirstOrDefault(x => S(x, "key") == key);
                    if (old is not null)
                    {
                        if (S(old, "product") != productId || I(old, "quantity") != quantity) throw new RuleError(409, "idempotency_conflict");
                        return Copy(old);
                    }
                    var product = Find("products", productId);
                    if (I(product, "stock") < quantity) throw new RuleError(409, "out_of_stock");
                    return Commit("OrderPlaced", Obj("id", Id(), "key", key, "product", productId, "quantity", quantity, "totalCents", I(product, "priceCents") * quantity));
                case "posts":
                    return Commit("PostPublished", Obj("id", Id(), "user", Text(request, "user"), "text", Text(request, "text", 280)));
                case "follows":
                    var user = Text(request, "user"); var target = Text(request, "target");
                    if (user == target) throw new RuleError(422, "self_follow");
                    var followId = user + "\n" + target;
                    return Table("follows").TryGetValue(followId, out var follow) ? Copy(follow) : Commit("FollowAdded", Obj("id", followId, "user", user, "target", target));
                case "drivers":
                    var driverId = Text(request, "id");
                    var available = !Table("drivers").TryGetValue(driverId, out var existing) || existing["available"]!.GetValue<bool>();
                    return Commit("DriverLocated", Obj("id", driverId, "lat", Coordinate(request, "lat", 90), "lon", Coordinate(request, "lon", 180), "available", available));
                case "rides":
                    var rider = Text(request, "user"); var lat = Coordinate(request, "lat", 90); var lon = Coordinate(request, "lon", 180);
                    var driver = Table("drivers").Values.Where(x => x["available"]!.GetValue<bool>()).OrderBy(x => Distance(lat, lon, x["lat"]!.GetValue<double>(), x["lon"]!.GetValue<double>())).ThenBy(x => S(x, "id"), StringComparer.Ordinal).FirstOrDefault();
                    if (driver is null) throw new RuleError(409, "no_driver_available");
                    return Commit("RideAssigned", Obj("id", Id(), "user", rider, "driver", S(driver, "id"), "status", "assigned", "lat", lat, "lon", lon));
                case "crawl":
                    var root = Text(request, "root");
                    if (request["pages"] is not JsonObject pages || pages.Count > 100 || !pages.ContainsKey(root)) throw new RuleError(422, "invalid_pages");
                    foreach (var (page, links) in pages)
                    {
                        if (page.Length is < 1 or > 100 || links is not JsonArray list || list.Count > 100 || list.Any(x => x is not JsonValue v || !v.TryGetValue<string>(out var str) || str.Length is < 1 or > 100)) throw new RuleError(422, "invalid_pages");
                    }
                    return Commit("CrawlQueued", Obj("id", Id(), "root", root, "pages", pages, "status", "queued"));
                case "files":
                    var fileId = Text(request, "id"); var owner = Text(request, "user"); var name = Text(request, "name", 255); var content = Text(request, "content", 90000);
                    byte[] bytes; try { bytes = Convert.FromBase64String(content); } catch (FormatException) { throw new RuleError(422, "invalid_content"); }
                    if (Convert.ToBase64String(bytes) != content) throw new RuleError(422, "invalid_content");
                    if (bytes.Length > 65536) throw new RuleError(422, "file_too_large");
                    var prior = Table("files").GetValueOrDefault(fileId);
                    if (prior is not null && S(prior, "user") != owner) throw new RuleError(403, "file_owner_mismatch");
                    var expected = Number(request, "expectedVersion", 0, int.MaxValue);
                    var version = prior is null ? 0 : I(prior, "version");
                    if (expected != version) throw new RuleError(409, "version_conflict");
                    return Commit("FileVersionAdded", Obj("id", fileId, "user", owner, "name", name, "content", content, "version", version + 1, "bytes", bytes.Length, "sha256", Convert.ToHexString(SHA256.HashData(bytes)).ToLowerInvariant()));
                default:
                    if (route.StartsWith("rides/") && route.EndsWith("/complete"))
                    {
                        var ride = Find("rides", route.Split('/')[1]);
                        if (S(ride, "status") == "completed") return Copy(ride);
                        var completed = Copy(ride); completed["status"] = "completed";
                        return Commit("RideCompleted", completed);
                    }
                    throw new RuleError(404, "route_not_found");
            }
        }
    }
    public void RunNext()
    {
        lock (gate)
        {
            var job = Table("jobs").Values.FirstOrDefault(x => S(x, "status") == "queued");
            if (job is null) return;
            var pages = job["pages"]!.AsObject(); var seen = new HashSet<string>(); var queue = new Queue<string>(); var visited = new List<string>();
            queue.Enqueue(S(job, "root"));
            while (queue.TryDequeue(out var page))
            {
                if (!seen.Add(page) || !pages.ContainsKey(page)) continue;
                visited.Add(page);
                foreach (var link in pages[page]!.AsArray()) queue.Enqueue(link!.GetValue<string>());
            }
            var done = Copy(job); done["status"] = "completed"; done["visited"] = System.Text.Json.JsonSerializer.SerializeToNode(visited);
            Commit("CrawlCompleted", done);
        }
    }
    public JsonObject State()
    {
        lock (gate)
        {
            var state = new JsonObject();
            foreach (var (name, rows) in tables)
            {
                var values = rows.Values.Select(Copy).ToArray();
                if (name == "files") foreach (var row in values) row.Remove("content");
                if (name == "jobs") foreach (var row in values) row.Remove("pages");
                state[name] = new JsonArray(values.Cast<JsonNode>().ToArray());
            }
            state["eventCount"] = events.Count; state["cacheHits"] = cacheHits; state["cacheMisses"] = cacheMisses;
            return state;
        }
    }
    public JsonArray History() { lock (gate) return new JsonArray(events.TakeLast(100).Select(x => x.DeepClone()).ToArray()); }
    public JsonArray Feed(string user)
    {
        lock (gate)
        {
            var authors = Table("follows").Values.Where(x => S(x, "user") == user).Select(x => S(x, "target")).Append(user).ToHashSet();
            return new JsonArray(Table("posts").Values.Where(x => authors.Contains(S(x, "user"))).Reverse().Take(100).Select(x => x.DeepClone()).ToArray());
        }
    }
    public JsonObject FileVersion(string id, int version)
    {
        lock (gate)
        {
            if (version == 0) return Copy(Find("files", id));
            var e = events.LastOrDefault(x => S(x, "type") == "FileVersionAdded" && S(x["data"]!.AsObject(), "id") == id && I(x["data"]!.AsObject(), "version") == version);
            return e is null ? throw new RuleError(404, "version_not_found") : Copy(e["data"]!.AsObject());
        }
    }
    public string Resolve(string code)
    {
        lock (gate)
        {
            if (cache.TryGetValue(code, out var entry) && entry.Until > DateTime.UtcNow) { cacheHits++; return entry.Url; }
            cacheMisses++; var url = S(Find("links", code), "url");
            cache[code] = (url, DateTime.UtcNow.AddSeconds(30)); return url;
        }
    }
    private static double Distance(double a, double b, double c, double d)
    {
        const double r = Math.PI / 180;
        var h = Math.Pow(Math.Sin((c - a) * r / 2), 2) + Math.Cos(a * r) * Math.Cos(c * r) * Math.Pow(Math.Sin((d - b) * r / 2), 2);
        return 6371 * 2 * Math.Asin(Math.Sqrt(Math.Clamp(h, 0, 1)));
    }
    public void Dispose() => lease.Dispose();
}
