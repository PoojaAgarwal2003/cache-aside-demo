package com.example.cacheaside;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

class PurchaseCrashAcceptanceTest {
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private static final JsonMapper JSON = new JsonMapper();

    @ParameterizedTest
    @ValueSource(strings = {"BEFORE_COMMIT", "AFTER_COMMIT"})
    void ownedProcessDeathReconcilesInventoryAndJournalWithoutDuplicateSale(String phase) throws Exception {
        try (var database = new PostgresFixture(); var redis = new RedisFixture();
             var child = new Child(database, redis)) {
            int port = child.start(phase);
            var created = send(port, "POST", "/products", "{\"name\":\"Crash fixture\",\"price\":1,\"stock\":5}");
            long id = created.get("id").asLong();
            send(port, "POST", "/demo/stock/" + id + "/reconcile", null);
            var pendingHttp = HTTP.sendAsync(request(port, "POST",
                    "/products/" + id + "/purchase?strategy=REDIS_ASSISTED", "{\"quantity\":2}"),
                    HttpResponse.BodyHandlers.ofString());
            await().atMost(Duration.ofSeconds(15)).until(() -> Files.exists(child.boundary));
            assertThat(Files.readString(child.boundary)).isEqualTo(phase);
            child.kill();
            assertThatThrownBy(pendingHttp::join).isInstanceOf(CompletionException.class);

            int expectedSales = "BEFORE_COMMIT".equals(phase) ? 0 : 2;
            assertThat(scalar(database, "SELECT coalesce(sum(quantity),0) FROM purchase_ledger")).isEqualTo(expectedSales);
            assertThat(scalar(database, "SELECT stock FROM products WHERE id=" + id)).isEqualTo(5 - expectedSales);
            assertThat(scalar(database, "SELECT count(*) FROM stock_reservations WHERE state='PENDING'")).isEqualTo(1);
            String committedPurchase = text(database, "SELECT purchase_id::text FROM purchase_ledger");

            port = child.start("NONE");
            var before = send(port, "GET", "/demo/stock/" + id, null);
            assertThat(before.get("trusted").asBoolean()).isFalse();
            assertThat(before.get("unresolvedReservations").asInt()).isEqualTo(1);
            var reconciled = send(port, "POST", "/demo/stock/" + id + "/reconcile", null);
            assertThat(reconciled.get("unresolvedReservations").asInt()).isZero();
            assertThat(reconciled.get("databaseStock").asInt()).isEqualTo(5 - expectedSales);
            assertThat(reconciled.get("redis").get("stock").asInt()).isEqualTo(5 - expectedSales);
            assertThat(text(database, "SELECT state FROM stock_reservations"))
                    .isEqualTo(expectedSales == 0 ? "RELEASED" : "COMMITTED");
            var retried = send(port, "POST", "/products/" + id + "/purchase?strategy=REDIS_ASSISTED",
                    "{\"quantity\":2}");
            assertThat(retried.get("code").asString()).isEqualTo("SOLD");
            assertThat(retried.get("replayed").asBoolean()).isEqualTo(expectedSales != 0);
            if (expectedSales != 0) {
                assertThat(retried.get("purchaseId").asString()).isEqualTo(committedPurchase);
            }
            assertThat(scalar(database, "SELECT count(*) FROM purchase_ledger")).isEqualTo(1);
            assertThat(scalar(database, "SELECT coalesce(sum(quantity),0) FROM purchase_ledger")).isEqualTo(2);
            assertThat(scalar(database, "SELECT stock FROM products WHERE id=" + id)).isEqualTo(3);
            assertThat(scalar(database, "SELECT count(*) FROM purchase_requests WHERE outcome='IN_PROGRESS'")).isZero();
            child.kill();
        }
    }

    private JsonNode send(int port, String method, String path, String body) throws Exception {
        var response = HTTP.send(request(port, method, path, body), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isIn(200, 201);
        return JSON.readTree(response.body());
    }

    private HttpRequest request(int port, String method, String path, String body) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("X-Client-Id", "crash-buyer").header("Idempotency-Key", "same-after-restart")
                .header("Content-Type", "application/json").timeout(Duration.ofSeconds(20))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body)).build();
    }

    private int scalar(PostgresFixture database, String sql) throws Exception {
        try (var connection = DriverManager.getConnection(database.scopedUrl(), database.username, database.password);
             var statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getInt(1);
        }
    }

    private String text(PostgresFixture database, String sql) throws Exception {
        try (var connection = DriverManager.getConnection(database.scopedUrl(), database.username, database.password);
             var statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
            return result.next() ? result.getString(1) : null;
        }
    }

    private static final class Child implements AutoCloseable {
        private final PostgresFixture database;
        private final RedisFixture redis;
        private final Path directory = Files.createTempDirectory("flashsale-crash-child-");
        private final Path ready = directory.resolve("ready");
        private final Path boundary = directory.resolve("boundary");
        private final Path arguments = directory.resolve("java.args");
        private final Path log = directory.resolve("child.log");
        private Process process;

        Child(PostgresFixture database, RedisFixture redis) throws Exception {
            this.database = database;
            this.redis = redis;
        }

        int start(String phase) throws Exception {
            assertThat(process == null || !process.isAlive()).isTrue();
            Files.deleteIfExists(ready);
            Files.deleteIfExists(boundary);
            var args = new ArrayList<>(java.util.List.of("-cp",
                    System.getProperty("flashsale.integration.classpath"), PurchaseCrashChild.class.getName()));
            args.addAll(Arrays.asList(database.arguments()));
            args.addAll(Arrays.asList(redis.arguments()));
            args.addAll(java.util.List.of("--spring.profiles.active=test", "--crash.phase=" + phase,
                    "--crash.ready-file=" + ready, "--crash.boundary-file=" + boundary,
                    "--logging.file.path=" + directory));
            Files.writeString(arguments, String.join("\n", args.stream().map(Child::quote).toList()));
            String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
            process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                    "@" + arguments).directory(directory.toFile()).redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile())).start();
            try {
                await().atMost(Duration.ofSeconds(45)).until(() -> {
                    assertThat(process.isAlive()).as("Child process exited; log %s", log).isTrue();
                    return Files.exists(ready);
                });
                return Integer.parseInt(Files.readString(ready));
            } catch (RuntimeException | AssertionError failure) {
                System.err.println(Files.readString(log));
                throw failure;
            }
        }

        private static String quote(String value) {
            return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        }

        void kill() throws Exception {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
                assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue();
            }
        }

        @Override
        public void close() throws Exception {
            kill();
            java.util.List<Path> ownedFiles;
            try (var files = Files.walk(directory)) {
                ownedFiles = files.sorted(java.util.Comparator.reverseOrder()).toList();
            }
            // Windows can briefly retain redirected-log handles after process exit.
            for (Path file : ownedFiles) {
                await().atMost(Duration.ofSeconds(10)).ignoreException(java.nio.file.FileSystemException.class)
                        .until(() -> {
                            Files.deleteIfExists(file);
                            return true;
                        });
            }
        }
    }
}
