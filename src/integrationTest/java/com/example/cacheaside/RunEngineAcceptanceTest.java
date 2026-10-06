package com.example.cacheaside;

import com.example.cacheaside.cache.CacheCoordinator;
import com.example.cacheaside.cache.CacheProbe;
import com.example.cacheaside.product.ProductView;
import com.example.cacheaside.purchase.PurchaseProbe;
import com.example.cacheaside.purchase.PurchaseRequest;
import com.example.cacheaside.demo.RunStore;
import com.example.cacheaside.product.ProductService;
import com.example.cacheaside.web.ApiException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import tools.jackson.databind.JsonNode;

import static com.example.cacheaside.PurchaseTestApplication.body;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

class RunEngineAcceptanceTest {
    private static RedisFixture redis;
    private static PurchaseTestApplication app;

    @BeforeAll
    static void start() throws Exception {
        redis = new RedisFixture();
        var arguments = new ArrayList<>(List.of(redis.arguments()));
        arguments.add("--lab.rate-limit.enabled=true");
        arguments.add("--lab.read-delay-ms=300");
        app = new PurchaseTestApplication(Configuration.class, arguments.toArray(String[]::new));
        await().atMost(Duration.ofSeconds(10)).until(() -> app.context.getBean(CacheCoordinator.class).status().readiness()
                == CacheCoordinator.Readiness.READY);
    }

    @AfterAll
    static void stop() throws Exception {
        try { if (app != null) { app.close(); } }
        finally { if (redis != null) { redis.close(); } }
    }

    @Test
    void fiftyRealHttpBuyersUseDistinctIdentitiesAndPersistTenUnitSales() throws Exception {
        for (String strategy : List.of("ATOMIC_SQL", "PESSIMISTIC")) {
            UUID id = start("{\"strategy\":\"" + strategy + "\"}");
            JsonNode result = complete(id);
            assertThat(result.get("state").asString()).isEqualTo("COMPLETED");
            assertThat(result.get("httpAttempts").asInt()).isEqualTo(50);
            assertThat(result.get("httpResponses").asInt()).isEqualTo(50);
            long product = result.get("fixtures").get(0).get("productId").asLong();
            assertThat(app.stock(product)).isZero();
            assertThat(app.sold(product)).isEqualTo(10);
            assertThat(result.get("result").get("invariantVerdict").asString()).isEqualTo("PASS");
            assertThat(result.get("result").get("cases").get(0).get("conservation").asBoolean()).isTrue();
            assertThat(result.get("result").get("soldQuantity").asInt()).isEqualTo(10);
            assertThat(result.get("result").get("databaseWork").get("PURCHASE").get("writes").asLong()).isGreaterThan(50);
            assertThat(result.get("result").get("databaseWork").get("VERIFICATION").get("reads").asLong()).isGreaterThan(50);
            var attempts = app.context.getBean(RunStore.class).attempts(id);
            assertThat(attempts.stream().map(a -> a.get("clientId")).distinct()).hasSize(50);
            assertThat(attempts).allSatisfy(attempt -> {
                JsonNode response = (JsonNode) attempt.get("response");
                assertThat(response.get("requestId").asString()).isNotBlank();
                assertThat(response.get("runId").asString()).isEqualTo(id.toString());
                assertThat(response.get("rateLimit").asString()).isEqualTo("ALLOWED");
                assertThat((Integer) attempt.get("httpStatus")).isIn(200, 409);
            });
            assertThat(body(app.request("GET", "/demo/runs/" + id + "/export", null, "observer", "x"))
                    .get("attempts").size()).isEqualTo(50);
        }
    }

    @Test
    void quantityAccountingUsesIsolatedFixturesAndRepeatedRunsNeverReuseKeys() throws Exception {
        UUID first = start("{\"buyers\":5,\"concurrency\":5,\"stock\":10,\"quantity\":3}");
        JsonNode one = complete(first);
        UUID second = start("{\"buyers\":5,\"concurrency\":5,\"stock\":10,\"quantity\":3}");
        JsonNode two = complete(second);
        long a = one.get("fixtures").get(0).get("productId").asLong();
        long b = two.get("fixtures").get(0).get("productId").asLong();
        assertThat(a).isNotEqualTo(b);
        assertThat(app.sold(a)).isEqualTo(9);
        assertThat(app.stock(a)).isEqualTo(1);
        assertThat(app.sold(b)).isEqualTo(9);
        assertThat(one.get("result").get("uniqueSales").asInt()).isEqualTo(3);
        assertThat(one.get("result").get("soldQuantity").asInt()).isEqualTo(9);
        assertThat(one.get("result").get("invariantVerdict").asString()).isEqualTo("PASS");
        assertThat(app.jdbc.queryForObject("SELECT count(DISTINCT client_id) FROM demo_run_attempts WHERE run_id IN (?,?)",
                Integer.class, first, second)).isEqualTo(10);
    }

    @Test
    void activeRunRejectsOtherRunsFixtureMutationsAndGlobalCacheClear() throws Exception {
        var paused = new Pause();
        app.context.getBean(ControlledProbe.class).next.set(paused);
        UUID run = start("{\"scenario\":\"READ\",\"buyers\":100,\"concurrency\":1}");
        assertThat(paused.loaded.await(5, TimeUnit.SECONDS)).isTrue();
        long product = app.context.getBean(RunStore.class).fixtures(run).get(0).productId();
        try {
            assertThat(app.request("POST", "/demo/runs", "{}", "observer", "x").statusCode()).isEqualTo(409);
            assertThat(app.request("PATCH", "/products/" + product, "{\"stock\":999}", "observer", "x").statusCode()).isEqualTo(409);
            assertThat(app.request("GET", "/products/" + product, null, "observer", "x").statusCode()).isEqualTo(409);
            assertThat(app.request("DELETE", "/cache/products", null, "observer", "x").statusCode()).isEqualTo(409);
            assertThatThrownBy(() -> app.context.getBean(ProductService.class).update(product,
                    PurchaseTestApplication.JSON.readTree("{\"stock\":999}"))).isInstanceOf(ApiException.class);
            assertThat(app.request("POST", "/demo/runs/" + run + "/cancel", null, "observer", "x").statusCode()).isEqualTo(200);
            assertThat(app.context.getBean(RunStore.class).snapshot(run).get("active")).isEqualTo(true);
            assertThat(app.request("PATCH", "/products/" + product, "{\"stock\":999}", "observer", "x").statusCode()).isEqualTo(409);
        } finally { paused.release.countDown(); }
        var result = complete(run);
        assertThat(result.get("state").asString()).isEqualTo("CANCELLED");
        assertThat(result.get("result").get("quiescent").asBoolean()).isTrue();
        assertThat(result.get("httpAttempts").asInt()).isEqualTo(1);
        assertThat(result.get("result").get("databaseWork").get("USER_READ").get("reads").asInt()).isEqualTo(1);
        assertThat(app.stock(product)).isEqualTo(10);
        assertThat(app.request("GET", "/products/" + product, null, "observer", "x").statusCode()).isEqualTo(200);
    }

    @Test
    void invalidRunParametersNeverCreatePersistentOrActiveWork() throws Exception {
        for (String invalid : List.of("{\"buyers\":101}", "{\"concurrency\":51}", "{\"host\":\"example.com\"}")) {
            assertThat(app.request("POST", "/demo/runs", invalid, "observer", "x").statusCode()).isEqualTo(400);
        }

        assertThat(app.jdbc.queryForObject("SELECT count(*) FROM demo_runs WHERE active", Integer.class)).isZero();
    }

    @Test
    void cursorRetentionIsBoundedAndReportsExactTruncationWithoutLosingResults() throws Exception {
        UUID run = start("{\"buyers\":1,\"concurrency\":1}");
        complete(run);
        RunStore store = app.context.getBean(RunStore.class);
        var before = store.events(run, 0, 100);
        long cursor = (long) before.get("nextCursor");
        for (int i = 0; i < 300; i++) { store.event(run, "RETENTION_TEST", java.util.Map.of("index", i)); }
        var after = store.events(run, cursor, 100);
        assertThat(after.get("gap")).isEqualTo(true);
        assertThat(after.get("truncated")).isEqualTo(true);
        assertThat((long) after.get("droppedEvents")).isGreaterThanOrEqualTo(44);
        assertThat(app.jdbc.queryForObject("SELECT count(*) FROM demo_run_events WHERE run_id=?", Integer.class, run))
                .isEqualTo(256);
        assertThat(PurchaseTestApplication.JSON.valueToTree(store.snapshot(run)).get("result").get("soldQuantity").asInt())
                .isEqualTo(1);
    }

    private static UUID start(String parameters) throws Exception {
        var response = app.request("POST", "/demo/runs", parameters, "observer", "x");
        assertThat(response.statusCode()).as(response.body()).isEqualTo(202);
        return UUID.fromString(body(response).get("runId").asString());
    }

    @Test
    void failedFinalPersistenceKeepsRunFencedUntilExplicitKeyOnlyReconciliation() throws Exception {
        app.jdbc.execute("""
                CREATE FUNCTION reject_run_finish() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'test-only finalization failure'; END $$
                """);
        app.jdbc.execute("""
                CREATE TRIGGER reject_run_finish BEFORE UPDATE OF active ON demo_runs
                FOR EACH ROW WHEN (NEW.active=false) EXECUTE FUNCTION reject_run_finish()
                """);
        UUID run;
        try {
            run = start("{\"buyers\":1,\"concurrency\":1,\"stock\":5}");
            await().atMost(Duration.ofSeconds(10)).until(() -> {
                var snapshot = app.context.getBean(com.example.cacheaside.demo.RunEngine.class).snapshot(run);
                return (boolean) snapshot.get("finalizationBlocked");
            });
            assertThat(app.request("POST", "/demo/runs", "{}", "observer", "x").statusCode()).isEqualTo(409);
        } finally {
            app.jdbc.execute("DROP TRIGGER reject_run_finish ON demo_runs");
            app.jdbc.execute("DROP FUNCTION reject_run_finish()");
        }
        await().atMost(Duration.ofSeconds(10)).until(() ->
                app.request("POST", "/demo/runs/" + run + "/reconcile", null, "observer", "x").statusCode() == 200);
        var result = complete(run);
        assertThat(result.get("state").asString()).isEqualTo("INCONCLUSIVE");
        assertThat(result.get("httpAttempts").asInt()).isEqualTo(1);
        assertThat(result.get("result").get("soldQuantity").asInt()).isEqualTo(1);
        assertThat(result.get("result").get("keysReconciled").asInt()).isEqualTo(1);
    }

    @Test
    void cancellationWaitsForAcceptedTransactionAndCountsItsLaterCommit() throws Exception {
        var paused = new Pause();
        app.context.getBean(ControlledPurchase.class).next.set(paused);
        UUID run = start("{\"buyers\":5,\"concurrency\":1,\"stock\":10,\"quantity\":2}");
        assertThat(paused.loaded.await(5, TimeUnit.SECONDS)).isTrue();
        try {
            app.request("POST", "/demo/runs/" + run + "/cancel", null, "observer", "x");
            assertThat(app.context.getBean(RunStore.class).snapshot(run).get("active")).isEqualTo(true);
            assertThat(app.jdbc.queryForObject("""
                    SELECT count(*) FROM purchase_ledger WHERE product_id IN
                    (SELECT product_id FROM demo_run_fixtures WHERE run_id=?)
                    """, Integer.class, run)).isZero();
        } finally { paused.release.countDown(); }
        var result = complete(run);
        assertThat(result.get("state").asString()).isEqualTo("CANCELLED");
        assertThat(result.get("result").get("soldQuantity").asInt()).isEqualTo(2);
        assertThat(result.get("result").get("cases").get(0).get("finalStock").asInt()).isEqualTo(8);
        assertThat(result.get("httpAttempts").asInt()).isEqualTo(1);
    }

    private static JsonNode complete(UUID run) throws Exception {
        await().atMost(Duration.ofSeconds(45)).until(() ->
                !(boolean) app.context.getBean(RunStore.class).snapshot(run).get("active"));
        return body(app.request("GET", "/demo/runs/" + run, null, "observer", "x"));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Configuration {
        @Bean ControlledProbe controlledProbe() { return new ControlledProbe(); }
        @Bean ControlledPurchase controlledPurchase() { return new ControlledPurchase(); }
    }
    static class Pause {
        final CountDownLatch loaded = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
    }
    static class ControlledProbe implements CacheProbe {
        final AtomicReference<Pause> next = new AtomicReference<>();
        @Override public void afterLoad(long productId, ProductView value) {
            Pause paused = next.getAndSet(null);
            if (paused == null) { return; }
            paused.loaded.countDown();
            try { assertThat(paused.release.await(10, TimeUnit.SECONDS)).isTrue(); }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
        }
    }
    static class ControlledPurchase implements PurchaseProbe {
        final AtomicReference<Pause> next = new AtomicReference<>();
        @Override public void beforeCommit(PurchaseRequest request, UUID purchaseId) {
            Pause paused = next.getAndSet(null);
            if (paused == null) { return; }
            paused.loaded.countDown();
            try { assertThat(paused.release.await(10, TimeUnit.SECONDS)).isTrue(); }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
        }
    }
}
