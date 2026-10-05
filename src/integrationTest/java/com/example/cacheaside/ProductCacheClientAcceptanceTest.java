package com.example.cacheaside;

import com.example.cacheaside.cache.CacheEntry;
import com.example.cacheaside.cache.ProductCacheClient;
import com.example.cacheaside.cache.RedisAccess;
import com.example.cacheaside.product.ProductRead.WriteOutcome;
import com.example.cacheaside.product.ProductView;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class ProductCacheClientAcceptanceTest {
    private static RedisFixture server;
    private static LettuceConnectionFactory factory;
    private static StringRedisTemplate redis;
    private static ProductCacheClient cache;
    private final String epoch = UUID.randomUUID().toString();

    @BeforeAll
    static void start() throws Exception {
        server = new RedisFixture();
        factory = new LettuceConnectionFactory(server.host, server.port);
        factory.afterPropertiesSet();
        factory.start();
        redis = new StringRedisTemplate(factory);
        cache = new ProductCacheClient(new RedisAccess(redis), JsonMapper.builder().findAndAddModules().build(), "cache_test");
    }

    @AfterAll
    static void stop() throws Exception {
        if (factory != null) { factory.destroy(); }
        if (server != null) { server.close(); }
    }

    @Test
    void positiveAndNegativeEnvelopesHaveFixedBoundedTtl() {
        var capture = cache.capture(epoch, 1);
        assertThat(cache.fill(capture, product(1, 1), null)).isEqualTo(WriteOutcome.STORED);
        var first = cache.inspect(epoch, 1);
        assertThat(first.remainingTtlMs()).isBetween(299_000L, 360_000L);
        assertThat(cache.lookup(epoch, 1).kind()).isEqualTo(ProductCacheClient.LookupKind.HIT);
        assertThat(cache.inspect(epoch, 1).remainingTtlMs()).isLessThanOrEqualTo(first.remainingTtlMs());
        assertThat(cache.fill(cache.capture(epoch, 2), CacheEntry.of(null), null)).isEqualTo(WriteOutcome.STORED);
        assertThat(cache.lookup(epoch, 2).kind()).isEqualTo(ProductCacheClient.LookupKind.HIT_ABSENT);
        assertThat(cache.inspect(epoch, 2).remainingTtlMs()).isBetween(29_000L, 30_000L);
    }

    @Test
    void completedInvalidationRejectsOldPositiveAndNegativeFills() {
        for (var entry : new CacheEntry[]{product(1, 1), CacheEntry.of(null)}) {
            var captured = cache.capture(epoch, 1);
            cache.invalidate(epoch, 1);
            assertThat(cache.fill(captured, entry, null)).isEqualTo(WriteOutcome.REJECTED_GENERATION);
            assertThat(cache.lookup(epoch, 1).kind()).isEqualTo(ProductCacheClient.LookupKind.MISS);
            assertThat(cache.capture(epoch, 1).generation()).isNotEqualTo(captured.generation());
        }
    }

    @Test
    void missingOrExpiredGenerationsNeverNormalizeToAnOldValue() {
        var captured = cache.capture(epoch, 1);
        redis.expire(cache.key(epoch, 1, "generation"), Duration.ofMillis(5));
        await().atMost(Duration.ofSeconds(2)).until(() -> !redis.hasKey(cache.key(epoch, 1, "generation")));
        assertThat(cache.fill(captured, product(1, 1), null)).isEqualTo(WriteOutcome.REJECTED_GENERATION);
        assertThat(cache.capture(epoch, 1).generation()).isNotEqualTo(captured.generation());
        assertThat(redis.getExpire(cache.key(epoch, 1, "generation"))).isBetween(590L, 600L);
    }

    @Test
    void expiredOwnerCannotPublishOrDeleteSuccessorLease() {
        var capture = cache.capture(epoch, 1);
        var first = cache.acquire(epoch, 1);
        assertThat(first).isNotNull();
        assertThat(cache.acquire(epoch, 1)).isNull();
        redis.expire(cache.key(epoch, 1, "lock"), Duration.ofMillis(5));
        await().atMost(Duration.ofSeconds(2)).until(() -> !redis.hasKey(cache.key(epoch, 1, "lock")));
        var second = cache.acquire(epoch, 1);
        assertThat(second).isNotNull().isNotEqualTo(first);
        assertThat(cache.fill(capture, product(1, 1), first)).isEqualTo(WriteOutcome.REJECTED_LOCK);
        assertThat(cache.release(epoch, 1, first)).isFalse();
        assertThat(cache.fill(capture, product(1, 2), second)).isEqualTo(WriteOutcome.STORED);
        assertThat(cache.release(epoch, 1, second)).isTrue();
    }

    @Test
    void corruptUnknownSchemaWrongProductAndMissingExpiryAreNotHits() {
        for (String raw : new String[]{"{", "{\"schemaVersion\":7,\"kind\":\"ABSENT\",\"data\":null}",
                "{\"schemaVersion\":1,\"kind\":\"PRESENT\",\"data\":null}"}) {
            redis.opsForValue().set(cache.key(epoch, 1, "data"), raw, Duration.ofSeconds(30));
            assertThat(cache.lookup(epoch, 1).kind()).isEqualTo(ProductCacheClient.LookupKind.CORRUPT);
            assertThat(redis.hasKey(cache.key(epoch, 1, "data"))).isFalse();
        }
        redis.opsForValue().set(cache.key(epoch, 1, "data"), "{\"schemaVersion\":1,\"kind\":\"ABSENT\",\"data\":null}");
        assertThat(cache.lookup(epoch, 1).kind()).isEqualTo(ProductCacheClient.LookupKind.CORRUPT);
        redis.opsForValue().set(cache.key(epoch, 1, "data"),
                "{\"schemaVersion\":1,\"kind\":\"ABSENT\",\"data\":null}", Duration.ofSeconds(60));
        assertThat(cache.lookup(epoch, 1).kind()).isEqualTo(ProductCacheClient.LookupKind.CORRUPT);
        redis.opsForHash().put(cache.key(epoch, 1, "data"), "wrong", "type");
        assertThat(cache.lookup(epoch, 1).kind()).isEqualTo(ProductCacheClient.LookupKind.CORRUPT);
        cache.invalidate(epoch, 1);
        assertThat(cache.inspect(epoch, 1).presence()).isEqualTo(ProductCacheClient.Presence.ABSENT_OR_EXPIRED);
    }

    @Test
    void expiredDataAndOldEpochCannotServeInNewNamespace() {
        cache.fill(cache.capture(epoch, 1), product(1, 1), null);
        assertThat(cache.lookup(UUID.randomUUID().toString(), 1).kind()).isEqualTo(ProductCacheClient.LookupKind.MISS);
        redis.expire(cache.key(epoch, 1, "data"), Duration.ofMillis(5));
        await().atMost(Duration.ofSeconds(2)).until(() ->
                cache.lookup(epoch, 1).kind() == ProductCacheClient.LookupKind.MISS);
    }

    private CacheEntry product(long id, long version) {
        return CacheEntry.of(new ProductView(id, "Fixture", new BigDecimal("1.00"), 10, version, Instant.now()));
    }
}
