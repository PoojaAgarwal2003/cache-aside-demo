package com.example.cacheaside;

import com.example.cacheaside.cache.CacheCoordinator;
import com.example.cacheaside.cache.CacheProbe;
import com.example.cacheaside.cache.DbChangeListener;
import com.example.cacheaside.cache.ProductCacheClient;
import com.example.cacheaside.cache.ProductReadService;
import com.example.cacheaside.cache.RedisAccess;
import com.example.cacheaside.product.ProductService;
import com.example.cacheaside.product.ProductView;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.LongConsumer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;

import static com.example.cacheaside.PurchaseTestApplication.body;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class CacheResilienceAcceptanceTest {
    private static RedisFixture server;
    private static PurchaseTestApplication app;
    private static CacheCoordinator coordinator;
    private static DbChangeListener listener;
    private static ProductCacheClient cache;
    private static ProductReadService reads;
    private static RedisAccess access;
    private static StringRedisTemplate redis;
    private static ControlledProbe probe;

    @BeforeAll
    static void start() throws Exception {
        server = new RedisFixture();
        app = new PurchaseTestApplication(ProbeConfiguration.class, server.arguments());
        coordinator = app.context.getBean(CacheCoordinator.class);
        listener = app.context.getBean(DbChangeListener.class);
        cache = app.context.getBean(ProductCacheClient.class);
        reads = app.context.getBean(ProductReadService.class);
        access = app.context.getBean(RedisAccess.class);
        redis = app.context.getBean(StringRedisTemplate.class);
        probe = app.context.getBean(ControlledProbe.class);
        ready();
    }

    @AfterEach
    void reset() {
        probe.loaded = (id, value) -> { };
        probe.filled = id -> { };
    }

    @AfterAll
    static void stop() throws Exception {
        try { if (app != null) { app.close(); } }
        finally { if (server != null) { server.close(); } }
    }

    @Test
    void controlledColdReadersShareOneLoaderAndWaitersHit() throws Exception {
        long id = fixture();
        long loads = reads.metrics().get("databaseLoads");
        long waits = reads.metrics().get("waiters");
        var loaded = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        probe.loaded = (key, value) -> { loaded.countDown(); waitFor(release); };
        var executor = Executors.newFixedThreadPool(8);
        try {
            var owner = executor.submit(() -> get(id, true));
            assertThat(loaded.await(5, TimeUnit.SECONDS)).isTrue();
            var followers = new ArrayList<java.util.concurrent.Future<HttpResponse<String>>>();
            for (int i = 0; i < 7; i++) { followers.add(executor.submit(() -> get(id, true))); }
            await().atMost(Duration.ofSeconds(2)).until(() -> reads.metrics().get("waiters") >= waits + 7);
            release.countDown();
            assertThat(body(owner.get(10, TimeUnit.SECONDS)).get("cacheWriteOutcome").asString()).isEqualTo("STORED");
            for (var follower : followers) {
                var response = follower.get(10, TimeUnit.SECONDS);
                assertThat(response.statusCode()).isEqualTo(200);
                assertThat(body(response).get("source").asString()).isEqualTo("REDIS_CACHE_AFTER_WAIT");
            }
            assertThat(reads.metrics().get("databaseLoads") - loads).isEqualTo(1);
        } finally {
            release.countDown(); executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void disablingProtectionKeepsFencingButDemonstratesDuplicateLoads() throws Exception {
        long id = fixture();
        long loads = reads.metrics().get("databaseLoads");
        var loaded = new CountDownLatch(4);
        var release = new CountDownLatch(1);
        probe.loaded = (key, value) -> { loaded.countDown(); waitFor(release); };
        var executor = Executors.newFixedThreadPool(4);
        try {
            var futures = new ArrayList<java.util.concurrent.Future<HttpResponse<String>>>();
            for (int i = 0; i < 4; i++) { futures.add(executor.submit(() -> get(id, false))); }
            assertThat(loaded.await(5, TimeUnit.SECONDS)).isTrue();
            release.countDown();
            for (var future : futures) {
                assertThat(body(future.get(10, TimeUnit.SECONDS)).get("source").asString()).isEqualTo("DATABASE");
            }
            assertThat(reads.metrics().get("databaseLoads") - loads).isEqualTo(4);
        } finally {
            release.countDown(); executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void waiterDeadlineFallsBackWithoutPublishing() throws Exception {
        long id = fixture();
        var loaded = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var count = new AtomicInteger();
        probe.loaded = (key, value) -> {
            if (count.incrementAndGet() == 1) { loaded.countDown(); waitFor(release); }
        };
        var executor = Executors.newSingleThreadExecutor();
        try {
            var owner = executor.submit(() -> get(id, true));
            assertThat(loaded.await(5, TimeUnit.SECONDS)).isTrue();
            long start = System.nanoTime();
            var waiter = get(id, true);
            assertThat(Duration.ofNanos(System.nanoTime() - start).toMillis()).isBetween(2_800L, 8_000L);
            assertThat(body(waiter).get("source").asString()).isEqualTo("DATABASE_FALLBACK");
            assertThat(body(waiter).get("cacheWriteOutcome").asString()).isEqualTo("SKIPPED_UNAVAILABLE");
            assertThat(cache.lookup(coordinator.status().epoch(), id).kind()).isEqualTo(ProductCacheClient.LookupKind.MISS);
            release.countDown();
            assertThat(owner.get(10, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
        } finally {
            release.countDown(); executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void stalledOwnerLosesExpiredLeaseAndCannotOverwriteSuccessor() throws Exception {
        long id = fixture();
        var loaded = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var count = new AtomicInteger();
        probe.loaded = (key, value) -> {
            if (count.incrementAndGet() == 1) { loaded.countDown(); waitFor(release); }
        };
        var executor = Executors.newSingleThreadExecutor();
        try {
            var first = executor.submit(() -> get(id, true));
            assertThat(loaded.await(5, TimeUnit.SECONDS)).isTrue();
            String lock = cache.key(coordinator.status().epoch(), id, "lock");
            redis.expire(lock, Duration.ofMillis(5));
            await().atMost(Duration.ofSeconds(2)).until(() -> !redis.hasKey(lock));
            var second = get(id, true);
            assertThat(body(second).get("cacheWriteOutcome").asString()).isEqualTo("STORED");
            release.countDown();
            assertThat(body(first.get(10, TimeUnit.SECONDS)).get("cacheWriteOutcome").asString()).isEqualTo("REJECTED_LOCK");
            assertThat(body(get(id, true)).get("source").asString()).isEqualTo("REDIS_CACHE");
        } finally {
            release.countDown(); executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void bypassCannotCreateUnlimitedDatabaseWork() throws Exception {
        long id = fixture();
        listener.stop();
        var loaded = new CountDownLatch(8);
        var release = new CountDownLatch(1);
        probe.loaded = (key, value) -> { loaded.countDown(); waitFor(release); };
        var executor = Executors.newFixedThreadPool(8);
        try {
            var futures = new ArrayList<java.util.concurrent.Future<HttpResponse<String>>>();
            for (int i = 0; i < 8; i++) { futures.add(executor.submit(() -> get(id, true))); }
            assertThat(loaded.await(5, TimeUnit.SECONDS)).isTrue();
            long start = System.nanoTime();
            var rejected = get(id, true);
            assertThat(rejected.statusCode()).isEqualTo(503);
            assertThat(body(rejected).get("code").asString()).isEqualTo("DATABASE_OVERLOADED");
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
            release.countDown();
            for (var future : futures) {
                assertThat(body(future.get(10, TimeUnit.SECONDS)).get("source").asString()).isEqualTo("DATABASE_FALLBACK");
            }
        } finally {
            release.countDown(); executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
            listener.start(); ready();
        }
    }

    @Test
    void actualDataPreservingRedisRestartRequiresIndependentProductRecovery() throws Exception {
        long id = fixture();
        get(id, true);
        String epoch = coordinator.status().epoch();
        boolean stopped = false;
        try {
            synchronized (coordinator) {
                server.stopServer(true);
                stopped = true;
                var updated = app.context.getBean(ProductService.class)
                        .update(id, PurchaseTestApplication.JSON.readTree("{\"stock\":9}"));
                assertThat(updated.stock()).isEqualTo(9);
                assertThat(coordinator.status().readiness()).isEqualTo(CacheCoordinator.Readiness.BYPASS);
            }
            assertThat(app.stock(id)).isEqualTo(9);
            var fallback = get(id, true);
            assertThat(body(fallback).get("source").asString()).isEqualTo("DATABASE_FALLBACK");
            assertThat(body(fallback).get("data").get("stock").asInt()).isEqualTo(9);
            assertThat(app.request("DELETE", "/cache/products/" + id, null, "admin", "k").statusCode()).isEqualTo(503);
            await().atMost(Duration.ofSeconds(15)).until(() ->
                    access.status().get("product-cache").state().equals("OPEN"));
            assertThat(access.status().get("stock-admission").state()).isEqualTo("CLOSED");
            assertThat(access.status().get("rate-limit").state()).isEqualTo("CLOSED");
            listener.stop();
            server.restartServer();
            stopped = false;
            await().atMost(Duration.ofSeconds(15))
                    .ignoreExceptionsMatching(failure -> failure instanceof org.springframework.dao.DataAccessException)
                    .until(() -> redis.hasKey(cache.key(epoch, id, "data")));
            assertThat(redis.opsForValue().get(cache.key(epoch, id, "data"))).contains("\"stock\":5");
            for (int i = 0; i < 5; i++) { access.ping(RedisAccess.Domain.RATE_LIMIT); }
            assertThat(coordinator.status().readiness()).isEqualTo(CacheCoordinator.Readiness.BYPASS);
            assertThat(body(get(id, true)).get("data").get("stock").asInt()).isEqualTo(9);
            listener.start(); ready();
            assertThat(coordinator.status().epoch()).isNotEqualTo(epoch);
            assertThat(body(get(id, true)).get("data").get("stock").asInt()).isEqualTo(9);
            assertThat(redis.hasKey(cache.key(epoch, id, "data"))).isTrue();
        } finally {
            if (stopped) { server.restartServer(); }
            if (!listener.isRunning()) { listener.start(); }
            ready();
        }
    }

    @Test
    void unknownFillAcknowledgementBypassesEvenWhenRedisStoredTheValue() throws Exception {
        long id = fixture();
        String epoch = coordinator.status().epoch();
        probe.filled = key -> {
            coordinator.listenerDisconnected("Test recovery gate after real fill.");
            throw new RedisAccess.Unavailable(RedisAccess.Domain.PRODUCT_CACHE, "Injected response loss after Lua.");
        };
        try {
            var response = get(id, true);
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(body(response).get("cacheWriteOutcome").asString()).isEqualTo("FAILED");
            assertThat(coordinator.status().readiness()).isEqualTo(CacheCoordinator.Readiness.BYPASS);
            assertThat(redis.hasKey(cache.key(epoch, id, "data"))).isTrue();
            probe.filled = key -> { };
            assertThat(body(get(id, true)).get("source").asString()).isEqualTo("DATABASE_FALLBACK");
        } finally {
            coordinator.listenerConnected(); ready();
        }
        assertThat(coordinator.status().epoch()).isNotEqualTo(epoch);
    }

    private long fixture() {
        ready();
        long seen = listener.health().notifications();
        long id = app.fixture(5);
        await().atMost(Duration.ofSeconds(5)).until(() -> listener.health().notifications() > seen);
        return id;
    }

    private HttpResponse<String> get(long id, boolean protectedLoad) throws Exception {
        return app.request("GET", "/products/" + id + "?stampedeProtection=" + protectedLoad,
                null, UUID.randomUUID().toString(), "k");
    }

    private static void ready() {
        await().atMost(Duration.ofSeconds(25)).until(() -> coordinator.status().readiness() == CacheCoordinator.Readiness.READY);
    }

    private static void waitFor(CountDownLatch latch) {
        try { assertThat(latch.await(15, TimeUnit.SECONDS)).isTrue(); }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Controlled test load interrupted.", interrupted);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ProbeConfiguration {
        @Bean ControlledProbe cacheProbe() { return new ControlledProbe(); }
    }

    static class ControlledProbe implements CacheProbe {
        volatile BiConsumer<Long, ProductView> loaded = (id, value) -> { };
        volatile LongConsumer filled = id -> { };
        @Override public void afterLoad(long id, ProductView value) { loaded.accept(id, value); }
        @Override public void afterFill(long id) { filled.accept(id); }
    }
}
