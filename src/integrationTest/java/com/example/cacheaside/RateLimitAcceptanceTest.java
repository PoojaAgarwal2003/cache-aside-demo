package com.example.cacheaside;

import com.example.cacheaside.cache.CacheCoordinator;
import com.example.cacheaside.cache.CacheProbe;
import com.example.cacheaside.cache.RedisAccess;
import com.example.cacheaside.product.ProductView;
import com.example.cacheaside.ratelimit.RateLimiter;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import static com.example.cacheaside.PurchaseTestApplication.body;
import static com.example.cacheaside.PurchaseTestApplication.simultaneous;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class RateLimitAcceptanceTest {
    private static RedisFixture server;
    private static PurchaseTestApplication app;
    private static RateLimiter limiter;
    private static RedisAccess access;
    private static CacheCoordinator coordinator;
    private static StringRedisTemplate redis;
    private static ControlledProbe probe;

    @BeforeAll
    static void start() throws Exception {
        server = new RedisFixture();
        var arguments = new ArrayList<>(List.of(server.arguments()));
        arguments.add("--lab.rate-limit.enabled=true");
        app = new PurchaseTestApplication(ProbeConfiguration.class, arguments.toArray(String[]::new));
        limiter = app.context.getBean(RateLimiter.class);
        access = app.context.getBean(RedisAccess.class);
        coordinator = app.context.getBean(CacheCoordinator.class);
        redis = app.context.getBean(StringRedisTemplate.class);
        probe = app.context.getBean(ControlledProbe.class);
        ready();
    }

    @AfterAll
    static void stop() throws Exception {
        try { if (app != null) { app.close(); } }
        finally { if (server != null) { server.close(); } }
    }

    @Test
    void fifteenConcurrentRequestsWithinMeasuredWindowAllowTenAndRejectFive() throws Exception {
        long id = app.fixture(5);
        String client = UUID.randomUUID().toString();
        var tasks = new ArrayList<Callable<HttpResponse<String>>>();
        for (int i = 0; i < 15; i++) {
            tasks.add(() -> app.request("GET", "/products/" + id, null, client, "k"));
        }
        long started = System.nanoTime();
        var responses = simultaneous(tasks);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(10));
        assertThat(responses.stream().filter(r -> r.statusCode() == 200)).hasSize(10);
        assertThat(responses.stream().filter(r -> r.statusCode() == 429)).hasSize(5);
        for (var response : responses) {
            assertThat(response.headers().firstValue("X-RateLimit-Limit")).contains("10");
            if (response.statusCode() == 429) {
                assertThat(body(response).get("code").asString()).isEqualTo("RATE_LIMITED");
                assertThat(body(response).get("requestId").asString()).isNotBlank();
                assertThat(Integer.parseInt(response.headers().firstValue("Retry-After").orElseThrow())).isBetween(1, 10);
                assertThat(response.headers().firstValue("X-RateLimit-Remaining")).contains("0");
            }
        }
        assertThat(app.request("GET", "/products/" + id, null, UUID.randomUUID().toString(), "k").statusCode())
                .isEqualTo(200);
        assertThat(app.purchase(id, "ATOMIC_SQL", client, "blocked", 1).statusCode()).isEqualTo(429);
        assertThat(app.request("GET", "/%70roducts/" + id, null, client, "k").statusCode()).isEqualTo(429);
        assertThat(app.request("GET", "/products;matrix=value/" + id, null, client, "k").statusCode()).isEqualTo(429);
        assertThat(app.sold(id)).isZero();
        assertThat(app.request("GET", "/status", null, client, "k").statusCode()).isEqualTo(200);
        assertThat(app.request("GET", "/cache/status", null, client, "k").statusCode()).isEqualTo(200);
        assertThat(redis.getExpire(limiter.key(client))).isBetween(1L, 10L);
    }

    @Test
    void acceptedEntriesExpireNaturallyAndRejectedRequestsDoNotExtendTheWindow() {
        String client = UUID.randomUUID().toString();
        for (int i = 0; i < 10; i++) {
            assertThat(limiter.decide(client).outcome()).isEqualTo(RateLimiter.Outcome.ALLOWED);
        }
        String key = limiter.key(client);
        assertThat(redis.opsForZSet().zCard(key)).isEqualTo(10);
        long ttl = redis.getExpire(key, TimeUnit.MILLISECONDS);
        assertThat(ttl).isBetween(1L, 10_000L);
        assertThat(limiter.decide(client).outcome()).isEqualTo(RateLimiter.Outcome.REJECTED);
        assertThat(redis.getExpire(key, TimeUnit.MILLISECONDS)).isLessThanOrEqualTo(ttl);
        await().atMost(Duration.ofSeconds(12)).until(() -> !redis.hasKey(key));
        assertThat(limiter.decide(client).remaining()).isEqualTo(9);
    }

    @Test
    void missingIdentityUsesLocalAndInvalidIdentityIsRejectedBeforeRedis() throws Exception {
        long id = app.fixture(5);
        var first = app.request("GET", "/products/" + id, null, null, "k");
        var second = app.request("GET", "/products/" + id, null, "local", "k");
        assertThat(first.headers().firstValue("X-RateLimit-Remaining")).contains("9");
        assertThat(second.headers().firstValue("X-RateLimit-Remaining")).contains("8");
        for (String client : new String[]{"x".repeat(65), "invalid:identity"}) {
            var response = app.request("GET", "/products/" + id, null, client, "k");
            assertThat(response.statusCode()).isEqualTo(400);
            assertThat(response.headers().firstValue("X-RateLimit-Status")).isEmpty();
        }
    }

    @Test
    void deterministicClockSubstitutionTestsExactOpenLowerClosedUpperBoundary() throws Exception {
        String key = limiter.key(UUID.randomUUID().toString());
        redis.opsForZSet().add(key, "at-lower", 0);
        redis.opsForZSet().add(key, "inside", 1);
        redis.opsForZSet().add(key, "at-upper", 10_000);
        redis.opsForZSet().add(key, "future", 10_001);
        String source;
        try (var stream = new ClassPathResource("redis/rate-window.lua").getInputStream()) {
            source = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertThat(source).contains("redis.call('TIME')");
        // Only the clock expression is replaced, in a test-only script, not a production override.
        var atTen = new DefaultRedisScript<>(source.replace("redis.call('TIME')", "{10, 0}"), String.class);
        var rejected = PurchaseTestApplication.JSON.readTree(redis.execute(atTen, List.of(key), "2", "10000", "new"));
        assertThat(rejected.get("outcome").asString()).isEqualTo("REJECTED");
        assertThat(rejected.get("retryAfterSeconds").asInt()).isEqualTo(1);
        assertThat(redis.opsForZSet().range(key, 0, -1)).containsExactly("inside", "at-upper");
        var afterBoundary = new DefaultRedisScript<>(source.replace("redis.call('TIME')", "{10, 1000}"), String.class);
        var accepted = PurchaseTestApplication.JSON.readTree(redis.execute(afterBoundary, List.of(key), "2", "10000", "new"));
        assertThat(accepted.get("outcome").asString()).isEqualTo("ALLOWED");
        assertThat(redis.opsForZSet().range(key, 0, -1)).containsExactly("at-upper", "new");
        redis.expire(key, Duration.ofMillis(5));
        await().atMost(Duration.ofSeconds(2)).until(() -> !redis.hasKey(key));
        assertThat(limiter.decide(UUID.randomUUID().toString()).outcome()).isEqualTo(RateLimiter.Outcome.ALLOWED);
    }

    @Test
    void rateBreakerFailureDoesNotChangeProductReadinessOrAdmissionBreaker() throws Exception {
        long id = app.fixture(5);
        String client = UUID.randomUUID().toString();
        redis.opsForValue().set(limiter.key(client), "wrong-type", Duration.ofSeconds(10));
        try {
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                var response = app.request("GET", "/products/" + id, null, client, "k");
                assertThat(response.statusCode()).isEqualTo(200);
                assertThat(response.headers().firstValue("X-RateLimit-Status")).contains("BYPASSED");
                assertThat(response.headers().firstValue("X-RateLimit-Remaining")).isEmpty();
                assertThat(access.status().get("rate-limit").state()).isEqualTo("OPEN");
            });
            assertThat(access.status().get("product-cache").state()).isEqualTo("CLOSED");
            assertThat(access.status().get("stock-admission").state()).isEqualTo("CLOSED");
            assertThat(coordinator.status().readiness()).isEqualTo(CacheCoordinator.Readiness.READY);
        } finally {
            recoverLimiter();
        }
    }

    @Test
    void actualRedisOutageFailsOpenWithoutQuotaButStillEnforcesDatabaseBulkhead() throws Exception {
        long id = app.fixture(5);
        var loaded = new CountDownLatch(8);
        var release = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(8);
        probe.loaded = (key, value) -> {
            loaded.countDown();
            try { assertThat(release.await(15, TimeUnit.SECONDS)).isTrue(); }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
        };
        try {
            server.stopServer();
            var futures = new ArrayList<java.util.concurrent.Future<HttpResponse<String>>>();
            for (int i = 0; i < 8; i++) {
                futures.add(executor.submit(() ->
                        app.request("GET", "/products/" + id, null, UUID.randomUUID().toString(), "k")));
            }
            assertThat(loaded.await(10, TimeUnit.SECONDS)).isTrue();
            var rejected = app.request("GET", "/products/" + id, null, UUID.randomUUID().toString(), "k");
            assertThat(rejected.statusCode()).isEqualTo(503);
            assertThat(body(rejected).get("code").asString()).isEqualTo("DATABASE_OVERLOADED");
            assertBypassed(rejected);
            release.countDown();
            for (var future : futures) {
                var response = future.get(10, TimeUnit.SECONDS);
                assertThat(response.statusCode()).isEqualTo(200);
                assertBypassed(response);
                assertThat(body(response).get("source").asString()).isEqualTo("DATABASE_FALLBACK");
            }
        } finally {
            release.countDown(); probe.loaded = (key, value) -> { };
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
            server.restartServer(); ready(); recoverLimiter();
        }
    }

    private void assertBypassed(HttpResponse<String> response) {
        assertThat(response.headers().firstValue("X-RateLimit-Status")).contains("BYPASSED");
        assertThat(response.headers().firstValue("X-RateLimit-Limit")).isEmpty();
        assertThat(response.headers().firstValue("X-RateLimit-Remaining")).isEmpty();
        assertThat(response.headers().firstValue("Retry-After")).isEmpty();
    }

    private static void ready() {
        await().atMost(Duration.ofSeconds(25)).until(() -> coordinator.status().readiness() == CacheCoordinator.Readiness.READY);
    }

    private static void recoverLimiter() {
        await().atMost(Duration.ofSeconds(25)).untilAsserted(() -> {
            limiter.decide(UUID.randomUUID().toString());
            assertThat(access.status().get("rate-limit").state()).isEqualTo("CLOSED");
        });
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ProbeConfiguration {
        @Bean ControlledProbe cacheProbe() { return new ControlledProbe(); }
    }

    static class ControlledProbe implements CacheProbe {
        volatile BiConsumer<Long, ProductView> loaded = (key, value) -> { };
        @Override public void afterLoad(long id, ProductView value) { loaded.accept(id, value); }
    }
}
