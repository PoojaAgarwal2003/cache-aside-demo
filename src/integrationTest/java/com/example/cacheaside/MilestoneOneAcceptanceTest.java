package com.example.cacheaside;

import com.example.cacheaside.product.Product;
import com.example.cacheaside.product.ProductInput;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.OptimisticLockException;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.PGConnection;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MilestoneOneAcceptanceTest {
    private static final PostgresFixture DATABASE = new PostgresFixture();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3)).build();
    private static final JsonMapper JSON = new JsonMapper();

    private static int port;
    private static JdbcTemplate jdbc;
    private static ConfigurableApplicationContext application;

    @BeforeAll
    static void startApplication() {
        // Spring 7's SpringExtension requires JUnit 6. Keep JUnit 5 and own the
        // real Boot lifecycle rather than downgrading Spring or mocking storage.
        application = SpringApplication.run(CacheAsideApplication.class, DATABASE.arguments());
        port = ((WebServerApplicationContext) application).getWebServer().getPort();
        jdbc = application.getBean(JdbcTemplate.class);
    }

    @AfterAll
    static void cleanup() throws Exception {
        if (application != null) {
            application.close();
        }
        DATABASE.close();
    }

    @Test
    void createsReadsPartiallyUpdatesAndDeletesWithoutDoubleVersionIncrement() throws Exception {
        var created = request("POST", "/products",
                "{\"name\":\"Laptop\",\"price\":1299.00,\"stock\":10}");
        assertThat(created.statusCode()).isEqualTo(201);
        var product = body(created);
        long id = product.get("id").asLong();
        assertThat(created.headers().firstValue("Location")).contains("/products/" + id);
        assertThat(product.get("version").asLong()).isZero();
        assertThat(product.get("updatedAt").isString()).isTrue();

        var patch = request("PATCH", "/products/" + id, "{\"stock\":7}");
        assertThat(patch.statusCode()).isEqualTo(200);
        assertThat(body(patch).get("version").asLong()).isEqualTo(1);
        assertThat(body(patch).get("name").asString()).isEqualTo("Laptop");
        var alias = request("PUT", "/products/" + id, "{\"name\":\"Edited\"}");
        assertThat(body(alias).get("stock").asInt()).isEqualTo(7);
        assertThat(body(alias).get("version").asLong()).isEqualTo(2);

        jdbc.update("UPDATE products SET stock=6, version=999 WHERE id=?", id);
        var read = request("GET", "/products/" + id, null);
        assertThat(body(read).get("data").get("version").asLong()).isEqualTo(3);
        assertThat(body(read).get("data").get("stock").asInt()).isEqualTo(6);
        assertThat(body(read).get("source").asString()).isEqualTo("DATABASE");
        assertThat(request("DELETE", "/products/" + id, null).statusCode()).isEqualTo(204);
        var absent = request("GET", "/products/" + id, null);
        assertThat(absent.statusCode()).isEqualTo(404);
        assertThat(body(absent).get("data").isNull()).isTrue();
        assertThat(request("DELETE", "/products/" + id, null).statusCode()).isEqualTo(404);
    }

    @Test
    void rejectsInvalidInputsWithSafeCorrelatedErrors() throws Exception {
        for (String invalid : new String[]{"{\"name\":\" \",\"price\":1,\"stock\":1}",
                "{\"name\":\"x\",\"price\":-1,\"stock\":1}",
                "{\"name\":\"x\",\"price\":1.001,\"stock\":1}",
                "{\"name\":\"x\",\"price\":1.000000000000000001,\"stock\":1}",
                "{\"name\":\"x\",\"price\":1,\"stock\":-1}",
                "{\"name\":\"x\",\"price\":1,\"stock\":1000001}",
                "{\"name\":\"x\",\"price\":1,\"stock\":\"1\"}",
                "{\"name\":\"x\",\"price\":1,\"stock\":1,\"version\":0}"}) {
            var response = request("POST", "/products", invalid);
            assertThat(response.statusCode()).as(invalid).isEqualTo(400);
            assertThat(body(response).get("code").asString()).isEqualTo("INVALID_REQUEST");
            assertThat(body(response).get("requestId").asString())
                    .isEqualTo(response.headers().firstValue("X-Request-Id").orElseThrow());
        }
        assertThat(request("GET", "/products/0", null).statusCode()).isEqualTo(400);
        assertThat(request("GET", "/products/not-a-number", null).statusCode()).isEqualTo(400);
        assertThat(request("POST", "/products", "x".repeat(8193)).statusCode()).isEqualTo(413);
    }

    @Test
    void rejectsCrossOriginMutations() throws Exception {
        var request = HttpRequest.newBuilder(uri("/products")).header("Origin", "https://example.org")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"x\",\"price\":1,\"stock\":1}"))
                .timeout(Duration.ofSeconds(10)).build();
        assertThat(HTTP.send(request, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(403);
    }

    @Test
    void notificationIsDeliveredOnlyAfterCommitAndNeverForRollback() throws Exception {
        long id = fixture(10);
        try (var listener = DriverManager.getConnection(DATABASE.scopedUrl(), DATABASE.username, DATABASE.password);
             var writer = DriverManager.getConnection(DATABASE.scopedUrl(), DATABASE.username, DATABASE.password);
             var listen = listener.createStatement();
             var update = writer.prepareStatement("UPDATE products SET stock=stock-1 WHERE id=?")) {
            listen.execute("LISTEN product_changes");
            var pg = listener.unwrap(PGConnection.class);
            writer.setAutoCommit(false);
            update.setLong(1, id);
            update.executeUpdate();
            assertThat(pg.getNotifications(100)).isNullOrEmpty();
            writer.rollback();
            assertThat(pg.getNotifications(100)).isNullOrEmpty();
            assertThat(jdbc.queryForObject("SELECT stock FROM products WHERE id=?", Integer.class, id)).isEqualTo(10);
            update.executeUpdate();
            writer.commit();
            var notifications = pg.getNotifications(2000);
            assertThat(notifications).hasSize(1);
            var payload = JSON.readTree(notifications[0].getParameter());
            assertThat(payload.get("operation").asString()).isEqualTo("UPDATE");
            assertThat(payload.get("productId").asLong()).isEqualTo(id);
            assertThat(payload.get("version").asLong()).isEqualTo(1);
        }
    }

    @Test
    void fiftyBuyersTenUnitsProducesTenUniqueSalesAndConservesInventory() throws Exception {
        long id = fixture(10);
        var tasks = new ArrayList<Callable<HttpResponse<String>>>();
        for (int buyer = 0; buyer < 50; buyer++) {
            String client = "run-" + id + "-buyer-" + buyer;
            tasks.add(() -> purchase(id, client, "first-purchase", "{\"quantity\":1}"));
        }
        var responses = simultaneous(tasks);
        assertThat(responses).filteredOn(response -> response.statusCode() == 200).hasSize(10);
        assertThat(responses).filteredOn(response -> response.statusCode() == 409).hasSize(40);
        assertThat(responses).allSatisfy(response -> assertThat(body(response).get("code").asString())
                .isIn("SOLD", "OUT_OF_STOCK"));
        assertThat(sold(id)).isEqualTo(10);
        assertThat(stock(id)).isZero();
        assertThat(sold(id) + stock(id)).isEqualTo(10);
        assertThat(jdbc.queryForObject("SELECT count(DISTINCT purchase_id) FROM purchase_ledger WHERE product_id=?",
                Integer.class, id)).isEqualTo(10);
    }

    @Test
    void concurrentSameKeyCommitsOnePurchaseAndReplaysTheOriginalSnapshot() throws Exception {
        long id = fixture(10);
        var tasks = new ArrayList<Callable<HttpResponse<String>>>();
        for (int attempt = 0; attempt < 20; attempt++) {
            tasks.add(() -> purchase(id, "same-" + id, "one-key", "{\"quantity\":2}"));
        }
        var responses = simultaneous(tasks);
        assertThat(responses).allSatisfy(response -> assertThat(response.statusCode()).isEqualTo(200));
        assertThat(responses.stream().map(this::body).filter(node -> !node.get("replayed").asBoolean()).count())
                .isEqualTo(1);
        assertThat(responses.stream().map(response -> body(response).get("purchaseId").asString()).distinct().count())
                .isEqualTo(1);
        assertThat(responses).allSatisfy(response -> {
            assertThat(body(response).get("stockLeft").asInt()).isEqualTo(8);
            assertThat(body(response).get("version").asLong()).isEqualTo(1);
        });
        assertThat(stock(id)).isEqualTo(8);
        assertThat(sold(id)).isEqualTo(2);
    }

    @Test
    void lostResponseReplayPayloadConflictAndClientScopesAreDistinct() throws Exception {
        long id = fixture(5);
        // Deliberately discard the first response, as a caller losing its result would.
        purchase(id, "lost-" + id, "same-key", null);
        var replay = purchase(id, "lost-" + id, "same-key", "{\"quantity\":1}");
        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(body(replay).get("replayed").asBoolean()).isTrue();
        assertThat(body(replay).get("stockLeft").asInt()).isEqualTo(4);
        var conflict = purchase(id, "lost-" + id, "same-key", "{\"quantity\":2}");
        assertThat(conflict.statusCode()).isEqualTo(409);
        assertThat(body(conflict).get("code").asString()).isEqualTo("IDEMPOTENCY_CONFLICT");
        var other = purchase(id, "other-" + id, "same-key", "{\"quantity\":1}");
        assertThat(other.statusCode()).isEqualTo(200);
        assertThat(body(other).get("replayed").asBoolean()).isFalse();
        assertThat(sold(id)).isEqualTo(2);
        assertThat(stock(id)).isEqualTo(3);
    }

    @Test
    void terminalRejectionsRemainStableAndQuantityGreaterThanOneIsConserved() throws Exception {
        long id = fixture(3);
        assertThat(purchase(id, "quantity-" + id, "two", "{\"quantity\":2}").statusCode()).isEqualTo(200);
        var insufficient = purchase(id, "quantity-" + id, "too-many", "{\"quantity\":2}");
        assertThat(insufficient.statusCode()).isEqualTo(409);
        assertThat(body(insufficient).get("code").asString()).isEqualTo("OUT_OF_STOCK");
        assertThat(stock(id) + sold(id)).isEqualTo(3);
        jdbc.update("UPDATE products SET stock=5 WHERE id=?", id);
        var replay = purchase(id, "quantity-" + id, "too-many", "{\"quantity\":2}");
        assertThat(body(replay).get("replayed").asBoolean()).isTrue();
        assertThat(body(replay).get("code").asString()).isEqualTo("OUT_OF_STOCK");
        assertThat(stock(id)).isEqualTo(5);
        assertThat(purchase(Long.MAX_VALUE, "absent", "absent-" + id, null).statusCode()).isEqualTo(404);
    }

    @Test
    void invalidPurchaseBodiesAndMissingKeysDoNotDecrement() throws Exception {
        long id = fixture(10);
        for (String invalid : new String[]{"null", "{}", "{\"quantity\":null}",
                "{\"quantity\":0}", "{\"quantity\":-1}", "{\"quantity\":1.5}",
                "{\"quantity\":\"1\"}", "{\"quantity\":1001}"}) {
            assertThat(purchase(id, "invalid-" + id, UUID.randomUUID().toString(), invalid).statusCode())
                    .as(invalid).isEqualTo(400);
        }
        assertThat(request("POST", "/products/" + id + "/purchase", "{\"quantity\":1}").statusCode())
                .isEqualTo(400);
        assertThat(stock(id)).isEqualTo(10);
        assertThat(sold(id)).isZero();
    }

    @Test
    void lockTimeoutRollsBackClaimAndSameKeyCanSucceedAfterRelease() throws Exception {
        long id = fixture(2);
        try (var blocker = DriverManager.getConnection(DATABASE.scopedUrl(), DATABASE.username, DATABASE.password);
             var lock = blocker.prepareStatement("SELECT id FROM products WHERE id=? FOR UPDATE")) {
            blocker.setAutoCommit(false);
            lock.setLong(1, id);
            try (var rows = lock.executeQuery()) {
                assertThat(rows.next()).isTrue();
            }
            long start = System.nanoTime();
            var failed = purchase(id, "blocked-" + id, "retry", null);
            assertThat(failed.statusCode()).isEqualTo(503);
            assertThat(body(failed).get("retryable").asBoolean()).isTrue();
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(8));
            blocker.rollback();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM purchase_requests WHERE product_id=?",
                Integer.class, id)).isZero();
        assertThat(purchase(id, "blocked-" + id, "retry", null).statusCode()).isEqualTo(200);
        assertThat(stock(id)).isEqualTo(1);
        assertThat(sold(id)).isEqualTo(1);
    }

    @Test
    void failureAfterDecrementRollsBackBothInventoryAndLedger() throws Exception {
        long id = fixture(2);
        String function = "reject_purchase_" + id;
        jdbc.execute("CREATE FUNCTION " + function + "() RETURNS trigger LANGUAGE plpgsql AS $$ "
                + "BEGIN IF NEW.product_id=" + id + " THEN RAISE EXCEPTION 'injected transaction failure'; "
                + "END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER " + function + " BEFORE UPDATE ON purchase_requests "
                + "FOR EACH ROW EXECUTE FUNCTION " + function + "()");
        try {
            var failed = purchase(id, "rollback-" + id, "same-key", null);
            assertThat(failed.statusCode()).isEqualTo(503);
            assertThat(failed.body()).doesNotContain("injected transaction failure", "UPDATE", "SQLException");
            assertThat(stock(id)).isEqualTo(2);
            assertThat(sold(id)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM purchase_requests WHERE product_id=?",
                    Integer.class, id)).isZero();
        } finally {
            jdbc.execute("DROP TRIGGER " + function + " ON purchase_requests");
            jdbc.execute("DROP FUNCTION " + function + "()");
        }
        assertThat(purchase(id, "rollback-" + id, "same-key", null).statusCode()).isEqualTo(200);
        assertThat(stock(id) + sold(id)).isEqualTo(2);
    }

    @Test
    void databaseRejectsAnAccidentallyUnfinishedClaimAtCommit() throws Exception {
        long id = fixture(1);
        try (var writer = DriverManager.getConnection(DATABASE.scopedUrl(), DATABASE.username, DATABASE.password);
             var claim = writer.prepareStatement("""
                     INSERT INTO purchase_requests(client_id,key_hash,fingerprint,product_id,quantity,strategy,original_request_id)
                     VALUES ('unfinished',?,?,?,1,'ATOMIC_SQL',?)
                     """)) {
            writer.setAutoCommit(false);
            claim.setString(1, "a".repeat(64));
            claim.setString(2, "b".repeat(64));
            claim.setLong(3, id);
            claim.setObject(4, UUID.randomUUID());
            claim.executeUpdate();
            assertThatThrownBy(writer::commit).isInstanceOf(java.sql.SQLException.class);
            writer.rollback();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM purchase_requests WHERE product_id=?",
                Integer.class, id)).isZero();
    }

    @Test
    void externalSqlInvalidatesAnAlreadyLoadedJpaVersion() {
        long id = fixture(10);
        try (var manager = application.getBean(EntityManagerFactory.class).createEntityManager()) {
            manager.getTransaction().begin();
            var old = manager.find(Product.class, id);
            jdbc.update("UPDATE products SET stock=8 WHERE id=?", id);
            old.update(new ProductInput("Stale editor", BigDecimal.ONE, 9));
            assertThatThrownBy(manager::flush).isInstanceOf(OptimisticLockException.class);
            manager.getTransaction().rollback();
        }
        assertThat(stock(id)).isEqualTo(8);
        assertThat(jdbc.queryForObject("SELECT version FROM products WHERE id=?", Long.class, id)).isEqualTo(1);
    }

    @Test
    void chunkedBodyCannotBypassTheByteLimit() throws Exception {
        var request = HttpRequest.newBuilder(uri("/products"))
                .header("Content-Type", "application/json").timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(new byte[8193])))
                .build();
        var response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(body(response).get("code").asString()).isEqualTo("BODY_TOO_LARGE");
    }

    private int stock(long id) {
        return jdbc.queryForObject("SELECT stock FROM products WHERE id=?", Integer.class, id);
    }

    private int sold(long id) {
        return jdbc.queryForObject("SELECT coalesce(sum(quantity),0) FROM purchase_ledger WHERE product_id=?",
                Integer.class, id);
    }

    private List<HttpResponse<String>> simultaneous(List<Callable<HttpResponse<String>>> tasks) throws Exception {
        var executor = Executors.newFixedThreadPool(tasks.size());
        var ready = new CountDownLatch(tasks.size());
        var start = new CountDownLatch(1);
        try {
            var futures = tasks.stream().map(task -> executor.submit(() -> {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Controlled start timed out");
                }
                return task.call();
            })).toList();
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            var results = new ArrayList<HttpResponse<String>>();
            for (var future : futures) {
                results.add(future.get(20, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private HttpResponse<String> purchase(long id, String client, String key, String body) throws Exception {
        var builder = HttpRequest.newBuilder(uri("/products/" + id + "/purchase"))
                .header("X-Client-Id", client).header("Idempotency-Key", key)
                .timeout(Duration.ofSeconds(15));
        if (body != null) {
            builder.header("Content-Type", "application/json");
        }
        return HTTP.send(builder.POST(body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private long fixture(int stock) {
        return jdbc.queryForObject("INSERT INTO products(name,price,stock) VALUES ('Fixture',1.00,?) RETURNING id",
                Long.class, stock);
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    private HttpResponse<String> request(String method, String path, String body) throws Exception {
        var builder = HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(15));
        if (body != null) {
            builder.header("Content-Type", "application/json");
        }
        return HTTP.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode body(HttpResponse<String> response) {
        return JSON.readTree(response.body());
    }
}
