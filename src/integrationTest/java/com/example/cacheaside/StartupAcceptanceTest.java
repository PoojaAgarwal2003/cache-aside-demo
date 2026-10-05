package com.example.cacheaside;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.Arrays;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StartupAcceptanceTest {
    @Test
    void upgradesExistingSchemaAndRestartsWithoutResettingProductsOrPurchaseKeys() throws Exception {
        try (var database = new PostgresFixture()) {
            var old = Flyway.configure().dataSource(database.scopedUrl(), database.username, database.password)
                    .schemas(database.schema).defaultSchema(database.schema).target("1").load();
            assertThat(old.migrate().migrationsExecuted).isEqualTo(1);
            long id;
            try (var connection = DriverManager.getConnection(database.scopedUrl(), database.username, database.password);
                 var statement = connection.createStatement();
                 var result = statement.executeQuery(
                         "INSERT INTO products(name,price,stock) VALUES ('Preserved edit',2.00,7) RETURNING id")) {
                assertThat(result.next()).isTrue();
                id = result.getLong(1);
            }
            var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
            var json = new JsonMapper();
            String purchaseId;
            try (var first = SpringApplication.run(CacheAsideApplication.class, database.arguments())) {
                var jdbc = first.getBean(JdbcTemplate.class);
                assertThat(jdbc.queryForObject("SELECT count(*) FROM products", Integer.class)).isEqualTo(1);
                assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE success AND type='SQL'",
                        Integer.class)).isEqualTo(5);
                var sale = http.send(purchase(first, id), HttpResponse.BodyHandlers.ofString());
                assertThat(sale.statusCode()).isEqualTo(200);
                purchaseId = json.readTree(sale.body()).get("purchaseId").asString();
            }
            try (var restarted = SpringApplication.run(CacheAsideApplication.class, database.arguments())) {
                var replay = http.send(purchase(restarted, id), HttpResponse.BodyHandlers.ofString());
                assertThat(replay.statusCode()).isEqualTo(200);
                var result = json.readTree(replay.body());
                assertThat(result.get("replayed").asBoolean()).isTrue();
                assertThat(result.get("purchaseId").asString()).isEqualTo(purchaseId);
                assertThat(result.get("stockLeft").asInt()).isEqualTo(6);
                var jdbc = restarted.getBean(JdbcTemplate.class);
                assertThat(jdbc.queryForObject("SELECT stock FROM products WHERE id=?", Integer.class, id)).isEqualTo(6);
                assertThat(jdbc.queryForObject("SELECT name FROM products WHERE id=?", String.class, id))
                        .isEqualTo("Preserved edit");
                assertThat(jdbc.queryForObject("SELECT count(*) FROM purchase_ledger", Integer.class)).isEqualTo(1);
            }
            var readOnlyArgs = Arrays.stream(database.arguments())
                    .map(value -> value.equals("--lab.demo-enabled=true") ? "--lab.demo-enabled=false" : value)
                    .toArray(String[]::new);
            try (var readOnly = SpringApplication.run(CacheAsideApplication.class, readOnlyArgs)) {
                assertThat(http.send(purchase(readOnly, id), HttpResponse.BodyHandlers.ofString()).statusCode())
                        .isEqualTo(403);
                assertThatThrownBy(() -> readOnly.getBean(com.example.cacheaside.purchase.PurchaseService.class)
                        .purchase(com.example.cacheaside.purchase.PurchaseRequest.parse(
                                id, null, "NONE", "read-only", "unsafe"), java.util.UUID.randomUUID()))
                        .isInstanceOf(com.example.cacheaside.web.ApiException.class)
                        .hasMessageContaining("deliberately unsafe");
                var read = HttpRequest.newBuilder(uri(readOnly, "/products/" + id))
                        .timeout(Duration.ofSeconds(10)).GET().build();
                assertThat(http.send(read, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
            }
        }
    }

    private HttpRequest purchase(ConfigurableApplicationContext app, long id) {
        return HttpRequest.newBuilder(uri(app, "/products/" + id + "/purchase"))
                .timeout(Duration.ofSeconds(10))
                .header("Idempotency-Key", "survives-restart")
                .POST(HttpRequest.BodyPublishers.noBody()).build();
    }

    private URI uri(ConfigurableApplicationContext app, String path) {
        int port = ((WebServerApplicationContext) app).getWebServer().getPort();
        return URI.create("http://127.0.0.1:" + port + path);
    }
}
