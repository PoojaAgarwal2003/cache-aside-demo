package com.example.cacheaside;

import com.example.cacheaside.cache.CacheCoordinator;
import com.example.cacheaside.cache.CacheProbe;
import com.example.cacheaside.cache.DbChangeListener;
import com.example.cacheaside.cache.ProductCacheClient;
import com.example.cacheaside.product.ProductView;
import java.net.http.HttpResponse;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;

import static com.example.cacheaside.PurchaseTestApplication.body;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class CacheInvalidationAcceptanceTest {
    private static RedisFixture server;
    private static PurchaseTestApplication app;
    private static CacheCoordinator coordinator;
    private static DbChangeListener listener;
    private static ProductCacheClient cache;
    private static ControlledProbe probe;

    @BeforeAll
    static void start() throws Exception {
        server = new RedisFixture();
        app = new PurchaseTestApplication(ProbeConfiguration.class, server.arguments());
        coordinator = app.context.getBean(CacheCoordinator.class);
        listener = app.context.getBean(DbChangeListener.class);
        cache = app.context.getBean(ProductCacheClient.class);
        probe = app.context.getBean(ControlledProbe.class);
        ready();
    }

    @AfterEach
    void reset() {
        probe.loaded = (id, value) -> { };
        probe.beforeListen = () -> { };
    }

    @AfterAll
    static void stop() throws Exception {
        try { if (app != null) { app.close(); } }
        finally { if (server != null) { server.close(); } }
    }

    @Test
    void coldAndWarmPositiveAndNegativeReadsExplainRealCacheWork() throws Exception {
        long id = fixture();
        var cold = get(id);
        assertThat(cold.statusCode()).isEqualTo(200);
        assertThat(cold.headers().firstValue("X-Cache")).contains("MISS");
        assertThat(body(cold).get("cacheWriteOutcome").asString()).isEqualTo("STORED");
        assertThat(body(get(id)).get("source").asString()).isEqualTo("REDIS_CACHE");
        long absent = id + 1_000_000;
        var missing = get(absent);
        assertThat(missing.statusCode()).isEqualTo(404);
        assertThat(body(missing).get("cacheWriteOutcome").asString()).isEqualTo("STORED");
        var cachedMissing = get(absent);
        assertThat(cachedMissing.statusCode()).isEqualTo(404);
        assertThat(cachedMissing.headers().firstValue("X-Cache")).contains("HIT");
        assertThat(body(cachedMissing).get("data").isNull()).isTrue();
        assertThat(body(cachedMissing).get("flow").toString()).contains(Long.toString(absent));
    }

    @Test
    void apiMutationsAndCommittedPurchasesInvalidateWithoutRepopulation() throws Exception {
        var created = app.request("POST", "/products", "{\"name\":\"API cache\",\"price\":2,\"stock\":5}", "admin", "k");
        long id = body(created).get("id").asLong();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(body(get(id)).get("source").asString()).isEqualTo("REDIS_CACHE"));
        assertThat(app.request("PATCH", "/products/" + id, "{\"name\":\"New name\"}", "admin", "k")
                .statusCode()).isEqualTo(200);
        assertMiss(id);
        assertThat(body(get(id)).get("data").get("name").asString()).isEqualTo("New name");
        assertThat(app.purchase(id, "ATOMIC_SQL", "cache-buyer", "key-" + id, 2).statusCode()).isEqualTo(200);
        assertMiss(id);
        assertThat(body(get(id)).get("data").get("stock").asInt()).isEqualTo(3);
        assertThat(app.request("DELETE", "/products/" + id, null, "admin", "k").statusCode()).isEqualTo(204);
        assertMiss(id);
        assertThat(get(id).statusCode()).isEqualTo(404);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void controlledOldPositiveOrNegativeFillCannotSurviveCommittedExternalChange(boolean negative) throws Exception {
        long id = negative ? app.jdbc.queryForObject("SELECT nextval('products_id_seq')", Long.class) : fixture();
        var loaded = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        probe.loaded = (productId, value) -> {
            if (productId == id) {
                loaded.countDown();
                waitFor(release);
            }
        };
        var executor = Executors.newSingleThreadExecutor();
        try {
            var old = executor.submit(() -> get(id));
            assertThat(loaded.await(5, TimeUnit.SECONDS)).isTrue();
            String generation = cache.capture(coordinator.status().epoch(), id).generation();
            if (negative) {
                app.jdbc.update("INSERT INTO products(id,name,price,stock) VALUES (?,'Created late',1,9)", id);
            } else {
                app.jdbc.update("UPDATE products SET stock=9 WHERE id=?", id);
            }
            await().atMost(Duration.ofSeconds(5)).until(() ->
                    !cache.capture(coordinator.status().epoch(), id).generation().equals(generation));
            release.countDown();
            var response = old.get(10, TimeUnit.SECONDS);
            assertThat(body(response).get("cacheWriteOutcome").asString()).isEqualTo("REJECTED_GENERATION");
            assertMiss(id);
            probe.loaded = (ignored, value) -> { };
            assertThat(body(get(id)).get("data").get("stock").asInt()).isEqualTo(9);
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void epochRotationWhileLoadPausedRejectsOldPublication() throws Exception {
        long id = fixture();
        var loaded = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        probe.loaded = (productId, value) -> { loaded.countDown(); waitFor(release); };
        var executor = Executors.newSingleThreadExecutor();
        try {
            var old = executor.submit(() -> get(id));
            assertThat(loaded.await(5, TimeUnit.SECONDS)).isTrue();
            String epoch = coordinator.status().epoch();
            var cleared = app.request("DELETE", "/cache/products", null, "admin", "k");
            assertThat(body(cleared).get("result").asString()).isEqualTo("INVALIDATED_NAMESPACE");
            assertThat(coordinator.status().epoch()).isNotEqualTo(epoch);
            release.countDown();
            assertThat(body(old.get(10, TimeUnit.SECONDS)).get("cacheWriteOutcome").asString())
                    .isEqualTo("REJECTED_GENERATION");
            assertMiss(id);
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void listenerConnectionLossBypassesAndReconnectDiscardsMissedNotificationEpoch() throws Exception {
        long id = fixture();
        get(id);
        String epoch = coordinator.status().epoch();
        var allowListen = new CountDownLatch(1);
        probe.beforeListen = () -> waitFor(allowListen);
        try {
            assertThat(app.jdbc.queryForObject("SELECT pg_terminate_backend(?)", Boolean.class,
                    listener.health().backendPid())).isTrue();
            await().atMost(Duration.ofSeconds(5)).until(() -> !coordinator.status().listenerHealthy());
            app.jdbc.update("UPDATE products SET stock=17 WHERE id=?", id);
            var fallback = get(id);
            assertThat(body(fallback).get("source").asString()).isEqualTo("DATABASE_FALLBACK");
            assertThat(body(fallback).get("data").get("stock").asInt()).isEqualTo(17);
            var redis = app.context.getBean(StringRedisTemplate.class);
            assertThat(redis.hasKey(cache.key(epoch, id, "data"))).isTrue();
            allowListen.countDown();
            ready();
            assertThat(coordinator.status().epoch()).isNotEqualTo(epoch);
            assertThat(body(get(id)).get("data").get("stock").asInt()).isEqualTo(17);
        } finally {
            allowListen.countDown();
            ready();
        }
    }

    @Test
    void rolledBackSqlDoesNotInvalidateAndOtherSchemaNotificationsAreIgnored() throws Exception {
        long id = fixture();
        get(id);
        String epoch = coordinator.status().epoch();
        String generation = cache.capture(epoch, id).generation();
        try (var connection = DriverManager.getConnection(app.database.scopedUrl(),
                app.database.username, app.database.password)) {
            connection.setAutoCommit(false);
            try (var update = connection.prepareStatement("UPDATE products SET stock=100 WHERE id=?")) {
                update.setLong(1, id);
                update.executeUpdate();
            }
            connection.rollback();
        }
        long seen = listener.health().notifications();
        app.jdbc.queryForObject("SELECT pg_notify('product_changes', ?)", String.class,
                "{\"schema\":\"other_schema\",\"productId\":" + id + "}");
        app.jdbc.queryForObject("SELECT pg_notify('product_changes', ?)", String.class,
                "{\"schema\":\"" + app.database.schema + "\",\"productId\":" + (id + 1_000_000) + "}");
        await().atMost(Duration.ofSeconds(5)).until(() -> listener.health().notifications() > seen);
        assertThat(cache.capture(epoch, id).generation()).isEqualTo(generation);
        assertThat(body(get(id)).get("data").get("stock").asInt()).isEqualTo(5);
        assertThat(app.request("DELETE", "/cache/products/" + (id + 9_000_000), null, "admin", "k")
                .statusCode()).isEqualTo(200);
    }

    private long fixture() {
        long seen = listener.health().notifications();
        long id = app.fixture(5);
        await().atMost(Duration.ofSeconds(5)).until(() -> listener.health().notifications() > seen);
        return id;
    }

    private HttpResponse<String> get(long id) throws Exception {
        return app.request("GET", "/products/" + id, null, "cache-reader", "k");
    }

    private void assertMiss(long id) {
        assertThat(cache.lookup(coordinator.status().epoch(), id).kind()).isEqualTo(ProductCacheClient.LookupKind.MISS);
    }

    private static void ready() {
        await().atMost(Duration.ofSeconds(15)).until(() -> coordinator.status().readiness() == CacheCoordinator.Readiness.READY);
    }

    private static void waitFor(CountDownLatch latch) {
        try {
            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Test boundary interrupted.", interrupted);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ProbeConfiguration {
        @Bean ControlledProbe cacheProbe() { return new ControlledProbe(); }
    }

    static class ControlledProbe implements CacheProbe {
        volatile BiConsumer<Long, ProductView> loaded = (id, value) -> { };
        volatile Runnable beforeListen = () -> { };
        @Override public void afterLoad(long id, ProductView value) { loaded.accept(id, value); }
        @Override public void beforeListen() { beforeListen.run(); }
    }
}
