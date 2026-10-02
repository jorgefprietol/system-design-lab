package lab;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.postgresql.ds.PGSimpleDataSource;

public final class Commerce {
    private final PGSimpleDataSource source;
    private final Semaphore connections = new Semaphore(16, true);
    public boolean configured() { return source != null; }
    public Commerce() throws IOException {
        String host = System.getenv("LAB_COMMERCE_HOST");
        if (host == null || host.isBlank()) { source = null; return; }
        String file = System.getenv("LAB_COMMERCE_PASSWORD_FILE");
        if (file == null) throw new IllegalStateException("Commerce password file is required");
        source = new PGSimpleDataSource();
        source.setServerNames(new String[]{host}); source.setDatabaseName("atlas_commerce");
        source.setUser("atlas_app"); source.setPassword(Files.readString(Path.of(file)).strip());
        source.setConnectTimeout(3); source.setSocketTimeout(10);
    }
    private ObjectNode query(String sql, Object... parameters) {
        if (source == null) throw new Lab.RuleError(503, "commerce_not_configured");
        boolean acquired = false;
        try {
            acquired = connections.tryAcquire(5, TimeUnit.SECONDS);
            if (!acquired) throw new Lab.RuleError(503, "commerce_unavailable");
            try (Connection connection = source.getConnection(); PreparedStatement command = connection.prepareStatement(sql)) {
                command.setQueryTimeout(5);
                for (int i = 0; i < parameters.length; i++) command.setObject(i + 1, parameters[i]);
                try (ResultSet result = command.executeQuery()) {
                    if (!result.next()) throw new SQLException("Empty commerce result");
                    return (ObjectNode) Lab.JSON.readTree(result.getString(1));
                }
            }
        } catch (SQLException e) {
            String code = e.getSQLState();
            String reason = e instanceof org.postgresql.util.PSQLException pg && pg.getServerErrorMessage() != null
                ? pg.getServerErrorMessage().getMessage() : "";
            if ("P0002".equals(code) && reason.equals("product_not_found")) throw new Lab.RuleError(404, reason);
            if ("P0001".equals(code) && java.util.Set.of("out_of_stock", "idempotency_conflict", "invalid_key", "invalid_product", "invalid_quantity").contains(reason))
                throw new Lab.RuleError(reason.startsWith("invalid_") ? 422 : 409, reason);
            throw new Lab.RuleError(503, "commerce_unavailable");
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new Lab.RuleError(503, "commerce_unavailable"); }
        catch (IOException e) { throw new IllegalStateException("Invalid commerce result", e); }
        finally { if (acquired) connections.release(); }
    }
    public ObjectNode health() {
        ObjectNode health = query("SELECT jsonb_build_object('schemaVersion', version, 'bootstrapSha256', bootstrap_sha256) FROM shop.schema_version WHERE version=1");
        health.put("status", "up"); health.put("implementation", "java");
        health.put("instance", System.getenv().getOrDefault("LAB_INSTANCE", "java"));
        health.put("revision", System.getenv().getOrDefault("LAB_REVISION", "development"));
        health.put("peerUrl", System.getenv().getOrDefault("LAB_COMMERCE_PEER_URL", ""));
        return health;
    }
    public ObjectNode state() { return query("""
        SELECT jsonb_build_object(
            'products', (SELECT coalesce(jsonb_agg(jsonb_build_object('id',id,'name',name,'priceCents',price_cents,'stock',stock) ORDER BY id),'[]'::jsonb) FROM shop.products),
            'orders', (SELECT coalesce(jsonb_agg(shop.receipt(o) ORDER BY placed_at,id),'[]'::jsonb) FROM shop.orders o),
            'orderCount', (SELECT count(*) FROM shop.orders),
            'unitsSold', (SELECT coalesce(sum(quantity),0) FROM shop.orders))
        """); }
    public ObjectNode order(ObjectNode request) {
        String key = text(request,"key"), product = text(request,"product");
        var quantity = request.get("quantity");
        if (quantity == null || !quantity.isIntegralNumber() || !quantity.canConvertToInt() || quantity.intValue() < 1 || quantity.intValue() > 100)
            throw new Lab.RuleError(422,"invalid_quantity");
        return query("SELECT shop.place_order(?,?,?)", key, product, quantity.intValue());
    }
    private static String text(ObjectNode request, String field) {
        var value = request.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank() || value.textValue().length() > 100 || value.textValue().chars().anyMatch(Character::isISOControl))
            throw new Lab.RuleError(422,"invalid_" + field);
        return value.textValue().strip();
    }
}
