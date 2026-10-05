package com.example.cacheaside.cache;

import com.example.cacheaside.product.ProductRead.WriteOutcome;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import static com.example.cacheaside.cache.RedisAccess.Domain.PRODUCT_CACHE;

@Component
public class ProductCacheClient {
    public enum LookupKind { HIT, HIT_ABSENT, MISS, UNAVAILABLE, CORRUPT }
    public enum Presence { PRESENT, ABSENT_OR_EXPIRED, CORRUPT, UNKNOWN }
    public record Lookup(LookupKind kind, CacheEntry entry, String error) {
        public boolean hit() { return kind == LookupKind.HIT || kind == LookupKind.HIT_ABSENT; }
    }
    public record Inspection(String availability, Presence presence, CacheEntry value, Long remainingTtlMs,
                             String error) { }
    public record Capture(String epoch, long productId, String generation, long startedNanos) { }

    public static final long GENERATION_TTL_MS = 600_000;
    public static final long MAX_FILL_MS = 8_000;
    public static final long LEASE_MS = 5_000;
    private static final Logger LOG = LoggerFactory.getLogger(ProductCacheClient.class);
    private static final DefaultRedisScript<String> GENERATION = RedisAccess.script("cache-generation");
    private static final DefaultRedisScript<String> INVALIDATE = RedisAccess.script("cache-invalidate");
    private static final DefaultRedisScript<String> FILL = RedisAccess.script("cache-fill");
    private static final DefaultRedisScript<String> INSPECT = RedisAccess.script("cache-inspect");
    private static final DefaultRedisScript<String> REMOVE = RedisAccess.script("cache-remove-corrupt");
    private static final DefaultRedisScript<String> ACQUIRE = RedisAccess.script("cache-acquire");
    private static final DefaultRedisScript<String> RELEASE = RedisAccess.script("cache-release");
    private final RedisAccess access;
    private final JsonMapper json;
    private final String namespace;

    public ProductCacheClient(RedisAccess access, JsonMapper json,
                              @Value("${spring.flyway.default-schema}") String namespace) {
        if (!namespace.matches("[A-Za-z0-9_]+")) {
            throw new IllegalArgumentException("Cache schema namespace must be safe ASCII.");
        }
        this.access = access;
        this.json = json;
        this.namespace = namespace;
    }

    public String key(String epoch, long id, String suffix) {
        UUID.fromString(epoch);
        return "flashsale:cache:{" + namespace + ":" + epoch + ":" + id + "}:" + suffix;
    }

    public Capture capture(String epoch, long id) {
        long started = System.nanoTime();
        String generation = access.execute(PRODUCT_CACHE, GENERATION, List.of(key(epoch, id, "generation")),
                UUID.randomUUID().toString(), Long.toString(GENERATION_TTL_MS));
        if ("CORRUPT".equals(generation)) {
            invalidate(epoch, id);
            throw new RedisAccess.Unavailable(PRODUCT_CACHE, "Generation metadata was corrupt.");
        }
        return new Capture(epoch, id, generation, started);
    }

    public void invalidate(String epoch, long id) {
        access.execute(PRODUCT_CACHE, INVALIDATE,
                List.of(key(epoch, id, "generation"), key(epoch, id, "data")),
                UUID.randomUUID().toString(), Long.toString(GENERATION_TTL_MS));
    }

    public WriteOutcome fill(Capture capture, CacheEntry entry, String owner) {
        if ((System.nanoTime() - capture.startedNanos()) / 1_000_000 >= MAX_FILL_MS) {
            return WriteOutcome.REJECTED_GENERATION;
        }
        if (!entry.validFor(capture.productId())) {
            throw new IllegalArgumentException("Cannot cache an invalid product envelope.");
        }
        long ttl = entry.kind() == CacheEntry.Kind.ABSENT ? 30_000
                : 300_000 + ThreadLocalRandom.current().nextInt(61) * 1_000L;
        String result = access.execute(PRODUCT_CACHE, FILL,
                List.of(key(capture.epoch(), capture.productId(), "generation"),
                        key(capture.epoch(), capture.productId(), "data"),
                        key(capture.epoch(), capture.productId(), "lock")),
                capture.generation(), owner == null ? "" : owner, json.writeValueAsString(entry),
                Long.toString(ttl), Long.toString(GENERATION_TTL_MS));
        return WriteOutcome.valueOf(result);
    }

    public String acquire(String epoch, long id) {
        String owner = UUID.randomUUID().toString();
        return "ACQUIRED".equals(access.execute(PRODUCT_CACHE, ACQUIRE, List.of(key(epoch, id, "lock")),
                owner, Long.toString(LEASE_MS))) ? owner : null;
    }

    public boolean release(String epoch, long id, String owner) {
        return "RELEASED".equals(access.execute(PRODUCT_CACHE, RELEASE, List.of(key(epoch, id, "lock")), owner));
    }

    public Lookup lookup(String epoch, long id) {
        Inspection view = inspect(epoch, id);
        return switch (view.presence()) {
            case PRESENT -> new Lookup(view.value().kind() == CacheEntry.Kind.ABSENT
                    ? LookupKind.HIT_ABSENT : LookupKind.HIT, view.value(), null);
            case ABSENT_OR_EXPIRED -> new Lookup(LookupKind.MISS, null, null);
            case CORRUPT -> new Lookup(LookupKind.CORRUPT, null, view.error());
            case UNKNOWN -> new Lookup(LookupKind.UNAVAILABLE, null, view.error());
        };
    }

    public Inspection inspect(String epoch, long id) {
        String raw = null;
        try {
            var snapshot = json.readTree(access.execute(PRODUCT_CACHE, INSPECT, List.of(key(epoch, id, "data"))));
            String presence = snapshot.get("presence").asString();
            if ("ABSENT_OR_EXPIRED".equals(presence)) {
                return new Inspection("AVAILABLE", Presence.ABSENT_OR_EXPIRED, null, null, null);
            }
            if ("CORRUPT".equals(presence)) {
                return corrupt(id, "Unexpected Redis value type; atomic type-guarded removal completed.");
            }
            raw = snapshot.get("value").asString();
            long ttl = snapshot.get("ttl").asLong();
            CacheEntry entry = raw.length() <= 32_768 ? json.readValue(raw, CacheEntry.class) : null;
            if (entry == null || !entry.validFor(id) || ttl <= 0 || ttl > 360_000) {
                removeCorrupt(epoch, id, raw);
                return corrupt(id, "Invalid schema, product fields or fixed expiry.");
            }
            return new Inspection("AVAILABLE", Presence.PRESENT, entry, ttl, null);
        } catch (JacksonException invalid) {
            if (raw != null) {
                removeCorrupt(epoch, id, raw);
            }
            return corrupt(id, "Invalid JSON cache envelope.");
        } catch (RedisAccess.Unavailable failure) {
            return new Inspection("UNAVAILABLE", Presence.UNKNOWN, null, null, failure.getMessage());
        }
    }

    private void removeCorrupt(String epoch, long id, String raw) {
        try {
            access.execute(PRODUCT_CACHE, REMOVE, List.of(key(epoch, id, "data")), raw);
        } catch (RedisAccess.Unavailable failure) {
            LOG.warn("Guarded corrupt-entry removal failed (productId={})", id);
        }
    }

    private Inspection corrupt(long id, String reason) {
        LOG.warn("Corrupt product cache (productId={}, reason={})", id, reason);
        return new Inspection("AVAILABLE", Presence.CORRUPT, null, null, reason);
    }
}
