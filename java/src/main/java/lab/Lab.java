package lab;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.node.*;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

public final class Lab implements AutoCloseable {
    public static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    public static final class RuleError extends RuntimeException {
        public final int status;
        public RuleError(int status, String code) { super(code); this.status = status; }
    }
    private final Path path;
    private final FileChannel leaseChannel;
    private final FileLock lease;
    private final List<ObjectNode> events = new ArrayList<>();
    private final Map<String, LinkedHashMap<String, ObjectNode>> tables = new LinkedHashMap<>();
    private record Entry(String url, long until) {}
    private final Map<String, Entry> cache = new HashMap<>();
    private long cacheHits, cacheMisses;

    public Lab(Path directory) throws IOException {
        Files.createDirectories(directory); path = directory.resolve("events.json");
        leaseChannel = FileChannel.open(directory.resolve("writer.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        lease = leaseChannel.tryLock();
        if (lease == null) { leaseChannel.close(); throw new IOException("Data directory already has a writer"); }
        try {
            for (String name : List.of("links", "messages", "products", "orders", "posts", "follows", "drivers", "rides", "jobs", "files")) tables.put(name, new LinkedHashMap<>());
            table("products").put("book", obj("id", "book", "name", "Arquitectura de sistemas", "priceCents", 2500, "stock", 5));
            table("products").put("keyboard", obj("id", "keyboard", "name", "Teclado mecánico", "priceCents", 6500, "stock", 8));
            if (Files.exists(path)) {
                JsonNode saved = JSON.readTree(Files.readAllBytes(path));
                if (!(saved instanceof ArrayNode)) throw new IOException("Invalid event log");
                for (JsonNode item : saved) {
                    if (!(item instanceof ObjectNode e) || e.path("sequence").asLong() != events.size() + 1L) throw new IOException("Invalid event sequence");
                    apply(e); events.add(e);
                }
            }
        } catch (Exception e) { lease.release(); leaseChannel.close(); throw e; }
    }
    public static ObjectNode obj(Object... values) {
        ObjectNode o = JSON.createObjectNode();
        for (int i = 0; i < values.length; i += 2) o.set((String)values[i], JSON.valueToTree(values[i + 1]));
        return o;
    }
    private LinkedHashMap<String, ObjectNode> table(String name) { return tables.get(name); }
    private static String text(ObjectNode o, String key, int max) {
        JsonNode v = o.get(key);
        if (v == null || !v.isTextual() || v.textValue().isBlank() || v.textValue().length() > max) throw new RuleError(422, "invalid_" + key);
        return v.textValue().strip();
    }
    private static String text(ObjectNode o, String key) { return text(o, key, 100); }
    private static int number(ObjectNode o, String key, int min, int max) {
        JsonNode v = o.get(key);
        if (v == null || !v.isIntegralNumber() || !v.canConvertToInt() || v.intValue() < min || v.intValue() > max) throw new RuleError(422, "invalid_" + key);
        return v.intValue();
    }
    private static double coordinate(ObjectNode o, String key, double limit) {
        JsonNode v = o.get(key);
        if (v == null || !v.isNumber() || !Double.isFinite(v.doubleValue()) || Math.abs(v.doubleValue()) > limit) throw new RuleError(422, "invalid_" + key);
        return v.doubleValue();
    }
    private ObjectNode find(String table, String id) {
        ObjectNode row = table(table).get(id);
        if (row == null) throw new RuleError(404, table + "_not_found");
        return row;
    }
    private static String id() { return UUID.randomUUID().toString().replace("-", ""); }
    private static String s(ObjectNode o, String key) { return o.get(key).textValue(); }
    private static int i(ObjectNode o, String key) { return o.get(key).intValue(); }
    private ObjectNode commit(String type, ObjectNode data) {
        ObjectNode e = obj("sequence", events.size() + 1, "time", System.currentTimeMillis(), "type", type, "data", data);
        ArrayNode next = JSON.createArrayNode(); events.forEach(next::add); next.add(e);
        Path tmp = path.resolveSibling("events.json.tmp");
        try {
            byte[] bytes = JSON.writeValueAsBytes(next);
            try (FileChannel stream = FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) stream.write(buffer); stream.force(true);
            }
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ex) { throw new java.io.UncheckedIOException(ex); }
        apply(e); events.add(e); return data.deepCopy();
    }
    private void apply(ObjectNode e) {
        ObjectNode d = ((ObjectNode)e.get("data")).deepCopy(); String type = s(e, "type");
        String table = switch (type) {
            case "LinkCreated" -> "links"; case "MessageSent" -> "messages"; case "OrderPlaced" -> "orders";
            case "PostPublished" -> "posts"; case "FollowAdded" -> "follows"; case "DriverLocated" -> "drivers";
            case "RideAssigned", "RideCompleted" -> "rides"; case "CrawlQueued", "CrawlCompleted" -> "jobs";
            case "FileVersionAdded" -> "files"; default -> throw new IllegalStateException("Unknown event");
        };
        table(table).put(s(d, "id"), d);
        if (type.equals("OrderPlaced")) { ObjectNode p = find("products", s(d, "product")); p.put("stock", i(p, "stock") - i(d, "quantity")); }
        if (type.equals("RideAssigned") || type.equals("RideCompleted")) find("drivers", s(d, "driver")).put("available", type.equals("RideCompleted"));
    }
    public synchronized ObjectNode command(String route, ObjectNode request) {
        switch (route) {
            case "links": {
                String url = text(request, "url", 2048);
                try {
                    URI uri = URI.create(url);
                    if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme())) || uri.getHost() == null) throw new IllegalArgumentException();
                } catch (IllegalArgumentException e) { throw new RuleError(422, "invalid_url"); }
                String code; do { code = id().substring(0, 12); } while (table("links").containsKey(code));
                return commit("LinkCreated", obj("id", code, "url", url));
            }
            case "messages": return commit("MessageSent", obj("id", id(), "room", text(request, "room"), "user", text(request, "user"), "text", text(request, "text", 2000)));
            case "orders": {
                String key = text(request, "key"), productId = text(request, "product"); int quantity = number(request, "quantity", 1, 100);
                ObjectNode old = table("orders").values().stream().filter(x -> s(x, "key").equals(key)).findFirst().orElse(null);
                if (old != null) {
                    if (!s(old, "product").equals(productId) || i(old, "quantity") != quantity) throw new RuleError(409, "idempotency_conflict");
                    return old.deepCopy();
                }
                ObjectNode product = find("products", productId);
                if (i(product, "stock") < quantity) throw new RuleError(409, "out_of_stock");
                return commit("OrderPlaced", obj("id", id(), "key", key, "product", productId, "quantity", quantity, "totalCents", i(product, "priceCents") * quantity));
            }
            case "posts": return commit("PostPublished", obj("id", id(), "user", text(request, "user"), "text", text(request, "text", 280)));
            case "follows": {
                String user = text(request, "user"), target = text(request, "target");
                if (user.equals(target)) throw new RuleError(422, "self_follow");
                String key = user + "\n" + target;
                ObjectNode old = table("follows").get(key);
                return old != null ? old.deepCopy() : commit("FollowAdded", obj("id", key, "user", user, "target", target));
            }
            case "drivers": {
                String driverId = text(request, "id"); ObjectNode prior = table("drivers").get(driverId);
                boolean available = prior == null || prior.get("available").booleanValue();
                return commit("DriverLocated", obj("id", driverId, "lat", coordinate(request, "lat", 90), "lon", coordinate(request, "lon", 180), "available", available));
            }
            case "rides": {
                String user = text(request, "user"); double lat = coordinate(request, "lat", 90), lon = coordinate(request, "lon", 180);
                ObjectNode driver = table("drivers").values().stream().filter(x -> x.get("available").booleanValue())
                    .min(Comparator.<ObjectNode>comparingDouble(x -> distance(lat, lon, x.get("lat").doubleValue(), x.get("lon").doubleValue())).thenComparing(x -> s(x, "id"))).orElse(null);
                if (driver == null) throw new RuleError(409, "no_driver_available");
                return commit("RideAssigned", obj("id", id(), "user", user, "driver", s(driver, "id"), "status", "assigned", "lat", lat, "lon", lon));
            }
            case "crawl": {
                String root = text(request, "root");
                if (!(request.get("pages") instanceof ObjectNode pages) || pages.size() > 100 || !pages.has(root)) throw new RuleError(422, "invalid_pages");
                var iterator = pages.fields();
                while (iterator.hasNext()) {
                    var entry = iterator.next();
                    if (entry.getKey().length() < 1 || entry.getKey().length() > 100 || !(entry.getValue() instanceof ArrayNode links) || links.size() > 100) throw new RuleError(422, "invalid_pages");
                    for (JsonNode link : links) if (!link.isTextual() || link.textValue().length() < 1 || link.textValue().length() > 100) throw new RuleError(422, "invalid_pages");
                }
                return commit("CrawlQueued", obj("id", id(), "root", root, "pages", pages, "status", "queued"));
            }
            case "files": {
                String fileId = text(request, "id"), owner = text(request, "user"), name = text(request, "name", 255), content = text(request, "content", 90000);
                byte[] bytes;
                try { bytes = Base64.getDecoder().decode(content); } catch (IllegalArgumentException e) { throw new RuleError(422, "invalid_content"); }
                if (!Base64.getEncoder().encodeToString(bytes).equals(content)) throw new RuleError(422, "invalid_content");
                if (bytes.length > 65536) throw new RuleError(422, "file_too_large");
                ObjectNode prior = table("files").get(fileId);
                if (prior != null && !s(prior, "user").equals(owner)) throw new RuleError(403, "file_owner_mismatch");
                int expected = number(request, "expectedVersion", 0, Integer.MAX_VALUE), version = prior == null ? 0 : i(prior, "version");
                if (expected != version) throw new RuleError(409, "version_conflict");
                try {
                    String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
                    return commit("FileVersionAdded", obj("id", fileId, "user", owner, "name", name, "content", content, "version", version + 1, "bytes", bytes.length, "sha256", hash));
                } catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
            }
            default:
                if (route.startsWith("rides/") && route.endsWith("/complete") && route.split("/").length == 3) {
                    ObjectNode ride = find("rides", route.split("/")[1]);
                    if (s(ride, "status").equals("completed")) return ride.deepCopy();
                    ObjectNode done = ride.deepCopy(); done.put("status", "completed"); return commit("RideCompleted", done);
                }
                throw new RuleError(404, "route_not_found");
        }
    }
    public synchronized void runNext() {
        ObjectNode job = table("jobs").values().stream().filter(x -> s(x, "status").equals("queued")).findFirst().orElse(null);
        if (job == null) return;
        ObjectNode pages = (ObjectNode)job.get("pages"); Set<String> seen = new HashSet<>(); Deque<String> queue = new ArrayDeque<>(); List<String> visited = new ArrayList<>(); queue.add(s(job, "root"));
        while (!queue.isEmpty()) {
            String page = queue.remove(); if (!seen.add(page) || !pages.has(page)) continue;
            visited.add(page); for (JsonNode link : pages.get(page)) queue.add(link.textValue());
        }
        ObjectNode done = job.deepCopy(); done.put("status", "completed"); done.set("visited", JSON.valueToTree(visited)); commit("CrawlCompleted", done);
    }
    public synchronized ObjectNode state() {
        ObjectNode state = JSON.createObjectNode();
        tables.forEach((name, rows) -> {
            ArrayNode values = state.putArray(name);
            rows.values().forEach(row -> { ObjectNode copy = row.deepCopy(); if (name.equals("files")) copy.remove("content"); if (name.equals("jobs")) copy.remove("pages"); values.add(copy); });
        });
        state.put("eventCount", events.size()); state.put("cacheHits", cacheHits); state.put("cacheMisses", cacheMisses); return state;
    }
    public synchronized ArrayNode history() {
        ArrayNode result = JSON.createArrayNode(); events.stream().skip(Math.max(0, events.size() - 100)).forEach(e -> result.add(e.deepCopy())); return result;
    }
    public synchronized ArrayNode feed(String user) {
        Set<String> authors = new HashSet<>(); authors.add(user);
        table("follows").values().stream().filter(x -> s(x, "user").equals(user)).forEach(x -> authors.add(s(x, "target")));
        List<ObjectNode> posts = new ArrayList<>(table("posts").values()); Collections.reverse(posts);
        ArrayNode result = JSON.createArrayNode(); posts.stream().filter(x -> authors.contains(s(x, "user"))).limit(100).forEach(x -> result.add(x.deepCopy())); return result;
    }
    public synchronized ObjectNode fileVersion(String id, int version) {
        if (version == 0) return find("files", id).deepCopy();
        return events.stream().filter(x -> s(x, "type").equals("FileVersionAdded"))
            .map(x -> (ObjectNode)x.get("data")).filter(x -> s(x, "id").equals(id) && i(x, "version") == version).findFirst()
            .map(ObjectNode::deepCopy).orElseThrow(() -> new RuleError(404, "version_not_found"));
    }
    public synchronized String resolve(String code) {
        Entry entry = cache.get(code);
        if (entry != null && entry.until > System.currentTimeMillis()) { cacheHits++; return entry.url; }
        cacheMisses++; String url = s(find("links", code), "url"); cache.put(code, new Entry(url, System.currentTimeMillis() + 30000)); return url;
    }
    private static double distance(double a, double b, double c, double d) {
        double r = Math.PI / 180, h = Math.pow(Math.sin((c - a) * r / 2), 2) + Math.cos(a * r) * Math.cos(c * r) * Math.pow(Math.sin((d - b) * r / 2), 2);
        return 6371 * 2 * Math.asin(Math.sqrt(Math.clamp(h, 0, 1)));
    }
    public void close() throws IOException { lease.release(); leaseChannel.close(); }
}
