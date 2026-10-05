package com.example.cacheaside;

import com.example.cacheaside.purchase.PurchaseProbe;
import com.example.cacheaside.purchase.PurchaseRequest;
import com.example.cacheaside.purchase.RedisStockClient;
import com.example.cacheaside.purchase.StockAdmissionService;
import com.example.cacheaside.web.ApiException;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.core.io.ClassPathResource;

import static com.example.cacheaside.PurchaseTestApplication.body;
import static com.example.cacheaside.PurchaseTestApplication.simultaneous;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class RedisAdmissionAcceptanceTest {
    private static RedisFixture server;
    private static PurchaseTestApplication app;
    private static ControlledProbe probe;
    private static StringRedisTemplate redis;
    private static RedisStockClient client;

    @BeforeAll
    static void start() throws Exception {
        server = new RedisFixture();
        app = new PurchaseTestApplication(ProbeConfiguration.class, server.arguments());
        probe = app.context.getBean(ControlledProbe.class);
        redis = app.context.getBean(StringRedisTemplate.class);
        client = app.context.getBean(RedisStockClient.class);
    }

    @AfterEach
    void resetProbe() {
        probe.reserved = (request, reservation) -> { };
        probe.committed = (request, purchaseId) -> { };
    }

    @AfterAll
    static void stop() throws Exception {
        try {
            if (app != null) {
                app.close();
            }
        } finally {
            if (server != null) {
                server.close();
            }
        }
    }

    @Test
    void fiftyBuyersConserveStockAndAdmissionRejectIsNotDatabaseOutOfStock() throws Exception {
        long id = fixture(10);
        var tasks = new ArrayList<Callable<HttpResponse<String>>>();
        for (int buyer = 0; buyer < 50; buyer++) {
            String identity = "redis-" + id + "-" + buyer;
            tasks.add(() -> buy(id, identity, "key", 1));
        }
        var responses = simultaneous(tasks);
        assertThat(responses).allSatisfy(response -> {
            assertThat(response.statusCode()).isIn(200, 409);
            assertThat(body(response).get("code").asString()).isIn("SOLD", "ADMISSION_REJECTED");
        });
        assertThat(app.stock(id)).isZero();
        assertThat(app.sold(id)).isEqualTo(10);
        assertThat(app.jdbc.queryForObject("SELECT count(DISTINCT purchase_id) FROM purchase_ledger WHERE product_id=?",
                Integer.class, id)).isEqualTo(10);
        assertThat(status(id).get("redis").get("stock").asInt()).isZero();
        assertThat(status(id).get("unresolvedReservations").asInt()).isZero();
    }

    @Test
    void sameKeyAndLegacyAliasReserveOnlyOnceAndReplayStableQuantitySnapshot() throws Exception {
        long id = fixture(10);
        var tasks = new ArrayList<Callable<HttpResponse<String>>>();
        for (int attempt = 0; attempt < 20; attempt++) {
            tasks.add(() -> app.purchase(id, "redis", "same-" + id, "key", 2));
        }
        var responses = simultaneous(tasks);
        assertThat(responses).allSatisfy(response -> {
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(body(response).get("stockLeft").asInt()).isEqualTo(8);
            assertThat(body(response).get("strategy").asString()).isEqualTo("REDIS_ASSISTED");
        });
        assertThat(responses.stream().map(response -> body(response).get("purchaseId").asString()).distinct().count())
                .isEqualTo(1);
        assertThat(app.jdbc.queryForObject("SELECT count(*) FROM stock_reservations WHERE product_id=?",
                Integer.class, id)).isEqualTo(1);
        assertThat(app.stock(id) + app.sold(id)).isEqualTo(10);
        assertThat(status(id).get("redis").get("stock").asInt()).isEqualTo(8);
        assertThat(body(buy(id, "same-" + id, "key", 1)).get("code").asString()).isEqualTo("IDEMPOTENCY_CONFLICT");
    }

    @Test
    void rejectionCanLeaveDatabaseStockAndIsStableAfterReset() throws Exception {
        long id = fixture(3);
        redis.opsForHash().put(client.counterKey(id), "stock", "0"); // Deliberately model advisory drift.
        var rejected = buy(id, "drift-" + id, "key", 2);
        assertThat(rejected.statusCode()).isEqualTo(409);
        assertThat(body(rejected).get("code").asString()).isEqualTo("ADMISSION_REJECTED");
        assertThat(app.stock(id)).isEqualTo(3);
        assertThat(app.sold(id)).isZero();
        reconcile(id);
        var replay = buy(id, "drift-" + id, "key", 2);
        assertThat(body(replay).get("code").asString()).isEqualTo("ADMISSION_REJECTED");
        assertThat(body(replay).get("replayed").asBoolean()).isTrue();
        assertThat(buy(id, "drift-" + id, "new", 2).statusCode()).isEqualTo(200);
        assertThat(app.sold(id) + app.stock(id)).isEqualTo(3);
    }

    @Test
    void rollbackCompensatesOnceAndSameKeyCanRetry() throws Exception {
        long id = fixture(5);
        UUID[] reservationId = new UUID[1];
        UUID[] epoch = new UUID[1];
        probe.reserved = (request, reservation) -> {
            reservationId[0] = reservation.id();
            epoch[0] = reservation.epoch();
            throw ApiException.unavailable("INJECTED_ROLLBACK", "Controlled failure after reserve.");
        };
        assertThat(buy(id, "rollback-" + id, "key", 2).statusCode()).isEqualTo(503);
        assertThat(app.stock(id)).isEqualTo(5);
        assertThat(app.sold(id)).isZero();
        assertThat(status(id).get("redis").get("stock").asInt()).isEqualTo(5);
        var release = new DefaultRedisScript<String>();
        release.setLocation(new ClassPathResource("redis/release-stock.lua"));
        release.setResultType(String.class);
        assertThat(redis.execute(release, java.util.List.of(client.counterKey(id),
                        client.reservationKey(id, reservationId[0])), epoch[0].toString(), "2")).isEqualTo("RELEASED");
        assertThat(status(id).get("redis").get("stock").asInt()).isEqualTo(5);
        assertThat(state(reservationId[0])).isEqualTo("RELEASED");
        resetProbe();
        assertThat(buy(id, "rollback-" + id, "key", 2).statusCode()).isEqualTo(200);
        assertThat(app.stock(id) + app.sold(id)).isEqualTo(5);
    }

    @Test
    void lostCommitResponseResolvesLedgerAndDoesNotRefundSoldStock() throws Exception {
        long id = fixture(5);
        probe.committed = (request, purchaseId) -> {
            throw ApiException.unavailable("INJECTED_UNKNOWN_COMMIT", "Simulated lost commit acknowledgement.");
        };
        assertThat(buy(id, "unknown-" + id, "key", 2).statusCode()).isEqualTo(503);
        assertThat(app.stock(id)).isEqualTo(3);
        assertThat(app.sold(id)).isEqualTo(2);
        assertThat(status(id).get("redis").get("stock").asInt()).isEqualTo(3);
        assertThat(app.jdbc.queryForObject("SELECT state FROM stock_reservations WHERE product_id=?",
                String.class, id)).isEqualTo("COMMITTED");
        resetProbe();
        var replay = buy(id, "unknown-" + id, "key", 2);
        assertThat(body(replay).get("replayed").asBoolean()).isTrue();
        assertThat(app.sold(id)).isEqualTo(2);
    }

    @Test
    void externalAndAdministrativeEditsDistrustEpochUntilExplicitReconciliation() throws Exception {
        long id = fixture(5);
        app.jdbc.update("UPDATE products SET stock=9 WHERE id=?", id);
        assertThat(status(id).get("trusted").asBoolean()).isFalse();
        var rejected = buy(id, "edited-" + id, "key", 1);
        assertThat(rejected.statusCode()).isEqualTo(503);
        assertThat(body(rejected).get("code").asString()).isEqualTo("ADMISSION_UNTRUSTED");
        assertThat(app.stock(id)).isEqualTo(9);
        var reset = reconcile(id);
        assertThat(body(reset).get("redis").get("stock").asInt()).isEqualTo(9);
        assertThat(buy(id, "edited-" + id, "key", 1).statusCode()).isEqualTo(200);
        var patched = app.request("PATCH", "/products/" + id, "{\"stock\":4}", "admin", "edit");
        assertThat(patched.statusCode()).isEqualTo(200);
        assertThat(status(id).get("trusted").asBoolean()).isFalse();
    }

    @Test
    void resetAndAdminMutationsRefuseAnActiveFixture() throws Exception {
        long id = fixture(5);
        var reserved = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        probe.reserved = (request, reservation) -> {
            reserved.countDown();
            try {
                assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
        };
        var executor = Executors.newSingleThreadExecutor();
        try {
            var sale = executor.submit(() -> buy(id, "drain-" + id, "key", 1));
            assertThat(reserved.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(reconcile(id).statusCode()).isEqualTo(409);
            assertThat(app.request("PATCH", "/products/" + id, "{\"stock\":9}", "admin", "edit").statusCode())
                    .isEqualTo(409);
            assertThat(app.request("DELETE", "/products/" + id, null, "admin", "delete").statusCode()).isEqualTo(409);
            release.countDown();
            assertThat(sale.get(10, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(reconcile(id).statusCode()).isEqualTo(200);
        assertThat(app.stock(id) + app.sold(id)).isEqualTo(5);
    }

    @Test
    void actualRedisShutdownReportsUnavailableAndFailedCompensationRemainsDurable() throws Exception {
        long id = fixture(5);
        probe.reserved = (request, reservation) -> {
            try {
                server.stopServer();
            } catch (Exception failure) {
                throw new IllegalStateException("Could not stop the owned Redis", failure);
            }
            throw ApiException.unavailable("INJECTED_ROLLBACK", "Known rollback during actual Redis outage.");
        };
        try {
            assertThat(buy(id, "outage-" + id, "key", 2).statusCode()).isEqualTo(503);
            assertThat(app.stock(id)).isEqualTo(5);
            assertThat(app.sold(id)).isZero();
            var status = status(id);
            assertThat(status.get("redis").get("availability").asString()).isEqualTo("UNAVAILABLE");
            assertThat(status.get("redis").get("presence").asString()).isEqualTo("UNKNOWN");
            assertThat(status.get("unresolvedReservations").asInt()).isEqualTo(1);
            resetProbe();
            var unavailable = buy(id, "outage-" + id, "retry", 1);
            assertThat(unavailable.statusCode()).isEqualTo(503);
            assertThat(body(unavailable).get("code").asString()).isEqualTo("REDIS_UNAVAILABLE");
            assertThat(app.stock(id)).isEqualTo(5);
            assertThat(app.purchase(id, "ATOMIC_SQL", "baseline-" + id, "key", 1).statusCode()).isEqualTo(200);
        } finally {
            resetProbe();
            server.restartServer();
        }
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(status(id).get("redis").get("availability").asString()).isEqualTo("AVAILABLE"));
        assertThat(reconcile(id).statusCode()).isEqualTo(200);
        assertThat(status(id).get("unresolvedReservations").asInt()).isZero();
        assertThat(status(id).get("redis").get("stock").asInt()).isEqualTo(4);
        assertThat(buy(id, "outage-" + id, "key", 2).statusCode()).isEqualTo(200);
        assertThat(app.stock(id) + app.sold(id)).isEqualTo(5);
    }

    private long fixture(int stock) throws Exception {
        long id = app.fixture(stock);
        assertThat(reconcile(id).statusCode()).isEqualTo(200);
        return id;
    }

    private HttpResponse<String> buy(long id, String identity, String key, int quantity) throws Exception {
        return app.purchase(id, "REDIS_ASSISTED", identity, key, quantity);
    }

    private HttpResponse<String> reconcile(long id) throws Exception {
        return app.request("POST", "/demo/stock/" + id + "/reconcile", null, "admin", "reset");
    }

    private tools.jackson.databind.JsonNode status(long id) throws Exception {
        var response = app.request("GET", "/demo/stock/" + id, null, "admin", "inspect");
        assertThat(response.statusCode()).isEqualTo(200);
        return body(response);
    }

    private String state(UUID reservation) {
        return app.jdbc.queryForObject("SELECT state FROM stock_reservations WHERE reservation_id=?",
                String.class, reservation);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ProbeConfiguration {
        @Bean
        ControlledProbe purchaseProbe() {
            return new ControlledProbe();
        }
    }

    static class ControlledProbe implements PurchaseProbe {
        volatile BiConsumer<PurchaseRequest, StockAdmissionService.Reservation> reserved = (request, reservation) -> { };
        volatile BiConsumer<PurchaseRequest, UUID> committed = (request, purchaseId) -> { };

        @Override
        public void afterReservation(PurchaseRequest request, StockAdmissionService.Reservation reservation) {
            reserved.accept(request, reservation);
        }

        @Override
        public void afterCommit(PurchaseRequest request, UUID purchaseId) {
            committed.accept(request, purchaseId);
        }
    }
}
