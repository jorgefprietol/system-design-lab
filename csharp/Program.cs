using System.Text.Json.Nodes;
using SystemDesignLab;

var builder = WebApplication.CreateBuilder(args);
builder.WebHost.UseUrls("http://" + (Environment.GetEnvironmentVariable("LAB_HOST") ?? "127.0.0.1") + ":" + (Environment.GetEnvironmentVariable("LAB_PORT") ?? "5081"));
builder.WebHost.ConfigureKestrel(k => k.Limits.MaxRequestBodySize = 131072);
var root = Environment.GetEnvironmentVariable("LAB_ROOT") ?? Path.GetFullPath("..");
builder.Services.AddSingleton(new Lab(Environment.GetEnvironmentVariable("LAB_DATA") ?? Path.Combine(root, "data", "csharp")));
builder.Services.AddHostedService<CrawlWorker>();
builder.Services.AddSingleton<Commerce>();
var app = builder.Build();
app.Use(async (ctx, next) =>
{
    ctx.Response.Headers["X-Content-Type-Options"] = "nosniff";
    if (ctx.Request.Path.StartsWithSegments("/api/commerce")) ctx.Response.Headers["X-Lab-Instance"] = Environment.GetEnvironmentVariable("LAB_INSTANCE") ?? Environment.MachineName;
    ctx.Response.Headers["Content-Security-Policy"] = "default-src 'self'; style-src 'self'; script-src 'self'; object-src 'none'; frame-ancestors 'none'";
    try { await next(ctx); }
    catch (RuleError e) { ctx.Response.StatusCode = e.Status; await ctx.Response.WriteAsJsonAsync(new { error = e.Message }); }
    catch (System.Text.Json.JsonException) { ctx.Response.StatusCode = 400; await ctx.Response.WriteAsJsonAsync(new { error = "invalid_json" }); }
    catch (BadHttpRequestException e) { ctx.Response.StatusCode = e.StatusCode; await ctx.Response.WriteAsJsonAsync(new { error = "invalid_request" }); }
    catch (Exception e) { app.Logger.LogError(e, "Request failed"); ctx.Response.StatusCode = 500; await ctx.Response.WriteAsJsonAsync(new { error = "internal_error" }); }
});
app.MapGet("/health", () => new { status = "up", implementation = "csharp", revision = Environment.GetEnvironmentVariable("LAB_REVISION") ?? "development" });
app.MapGet("/", () => Results.File(Path.Combine(root, "web", "index.html"), "text/html"));
app.MapGet("/app.js", () => Results.File(Path.Combine(root, "web", "app.js"), "text/javascript"));
app.MapGet("/style.css", () => Results.File(Path.Combine(root, "web", "style.css"), "text/css"));
app.MapGet("/commerce", () => Results.File(Path.Combine(root, "web", "commerce.html"), "text/html"));
app.MapGet("/commerce.js", () => Results.File(Path.Combine(root, "web", "commerce.js"), "text/javascript"));
app.MapGet("/api/commerce/health", (Commerce shop) => shop.Health());
app.MapGet("/api/commerce/state", (Commerce shop) => shop.State());
app.MapPost("/api/commerce/orders", async (HttpRequest request, Commerce shop) =>
{
    var body = await JsonNode.ParseAsync(request.Body);
    if (body is not JsonObject order) throw new RuleError(400, "invalid_json");
    return await shop.Order(order);
});
app.MapGet("/api/state", (Lab lab) => lab.State());
app.MapGet("/api/events", (Lab lab) => lab.History());
app.MapGet("/api/feed/{user}", (string user, Lab lab) => lab.Feed(user));
app.MapGet("/api/files/{id}", (string id, int? version, Lab lab) =>
{
    if (version is < 0) throw new RuleError(422, "invalid_version");
    return lab.FileVersion(id, version ?? 0);
});
app.MapGet("/r/{code}", (string code, Lab lab) => Results.Redirect(lab.Resolve(code), permanent: false));
app.MapPost("/api/{**route}", async (string route, HttpRequest request, Lab lab) =>
{
    var body = await JsonNode.ParseAsync(request.Body);
    if (body is not JsonObject obj) throw new RuleError(400, "invalid_json");
    return Results.Json(lab.Command(route, obj), statusCode: 200);
});
app.Run();

public sealed class CrawlWorker(Lab lab, ILogger<CrawlWorker> logger) : BackgroundService
{
    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        using var timer = new PeriodicTimer(TimeSpan.FromSeconds(1));
        while (await timer.WaitForNextTickAsync(stoppingToken))
        {
            try { lab.RunNext(); }
            catch (Exception e) { logger.LogError(e, "Crawl worker failed; queued job retained for retry"); }
        }
    }
}
