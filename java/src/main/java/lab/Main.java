package lab;

import com.sun.net.httpserver.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.concurrent.*;

public final class Main {
    public static void main(String[] args) throws Exception {
        Path root = Path.of(System.getenv().getOrDefault("LAB_ROOT", "..")).toAbsolutePath();
        Lab lab = new Lab(Path.of(System.getenv().getOrDefault("LAB_DATA", root.resolve("data/java").toString())));
        int port = Integer.parseInt(System.getenv().getOrDefault("LAB_PORT", "5082"));
        HttpServer server = HttpServer.create(new InetSocketAddress(System.getenv().getOrDefault("LAB_HOST", "127.0.0.1"), port), 64);
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor(); server.setExecutor(executor);
        ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();
        worker.scheduleWithFixedDelay(() -> { try { lab.runNext(); } catch (Exception e) { System.err.println("Worker failed; job retained: " + e); } }, 1, 1, TimeUnit.SECONDS);
        server.createContext("/", exchange -> handle(exchange, lab, root));
        Runtime.getRuntime().addShutdownHook(new Thread(() -> { server.stop(1); worker.shutdownNow(); executor.shutdownNow(); try { lab.close(); } catch (IOException e) { System.err.println(e); } }));
        server.start(); System.out.println("Java System Design Lab: http://127.0.0.1:" + port);
    }
    private static void handle(HttpExchange ex, Lab lab, Path root) throws IOException {
        try {
            ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            ex.getResponseHeaders().set("Content-Security-Policy", "default-src 'self'; style-src 'self'; script-src 'self'; object-src 'none'; frame-ancestors 'none'");
            String path = ex.getRequestURI().getPath(), method = ex.getRequestMethod();
            if (method.equals("POST") && path.startsWith("/api/")) {
                byte[] body = ex.getRequestBody().readNBytes(131073);
                if (body.length > 131072) throw new Lab.RuleError(413, "invalid_request");
                var parsed = Lab.JSON.readTree(body);
                if (!(parsed instanceof ObjectNode request)) throw new Lab.RuleError(400, "invalid_json");
                send(ex, 200, lab.command(path.substring(5), request)); return;
            }
            if (!method.equals("GET")) throw new Lab.RuleError(405, "method_not_allowed");
            switch (path) {
                case "/": staticFile(ex, root.resolve("web/index.html"), "text/html; charset=utf-8"); return;
                case "/app.js": staticFile(ex, root.resolve("web/app.js"), "text/javascript; charset=utf-8"); return;
                case "/style.css": staticFile(ex, root.resolve("web/style.css"), "text/css; charset=utf-8"); return;
                case "/health": send(ex, 200, Lab.obj("status", "up", "implementation", "java", "revision", System.getenv().getOrDefault("LAB_REVISION", "development"))); return;
                case "/api/state": send(ex, 200, lab.state()); return;
                case "/api/events": send(ex, 200, lab.history()); return;
                default:
                    if (path.startsWith("/api/feed/")) { send(ex, 200, lab.feed(path.substring(10))); return; }
                    if (path.startsWith("/api/files/")) {
                        int version = 0; String query = ex.getRequestURI().getRawQuery();
                        if (query != null) for (String pair : query.split("&")) if (pair.startsWith("version=")) {
                            try { version = Integer.parseInt(pair.substring(8)); } catch (NumberFormatException e) { throw new Lab.RuleError(422, "invalid_version"); }
                            if (version < 0) throw new Lab.RuleError(422, "invalid_version");
                        }
                        send(ex, 200, lab.fileVersion(path.substring(11), version)); return;
                    }
                    if (path.startsWith("/r/")) {
                        ex.getResponseHeaders().set("Location", lab.resolve(path.substring(3))); ex.sendResponseHeaders(302, -1); return;
                    }
                    throw new Lab.RuleError(404, "route_not_found");
            }
        } catch (Lab.RuleError e) { send(ex, e.status, Lab.obj("error", e.getMessage())); }
        catch (JsonProcessingException e) { send(ex, 400, Lab.obj("error", "invalid_json")); }
        catch (Exception e) { e.printStackTrace(); send(ex, 500, Lab.obj("error", "internal_error")); }
        finally { ex.close(); }
    }
    private static void send(HttpExchange ex, int status, Object body) throws IOException {
        byte[] bytes = Lab.JSON.writeValueAsBytes(body); ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length); ex.getResponseBody().write(bytes);
    }
    private static void staticFile(HttpExchange ex, Path file, String type) throws IOException {
        byte[] bytes = Files.readAllBytes(file); ex.getResponseHeaders().set("Content-Type", type); ex.sendResponseHeaders(200, bytes.length); ex.getResponseBody().write(bytes);
    }
}
