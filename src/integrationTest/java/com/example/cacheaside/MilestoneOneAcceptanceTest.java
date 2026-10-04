package com.example.cacheaside;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.DriverManager;
import java.time.Duration;
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
