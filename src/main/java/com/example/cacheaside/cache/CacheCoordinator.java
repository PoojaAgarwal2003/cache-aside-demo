package com.example.cacheaside.cache;

import com.example.cacheaside.product.ProductRead.WriteOutcome;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class CacheCoordinator {
    public enum Readiness { READY, BYPASS, RECOVERING }
    public record Status(Readiness readiness, String epoch, boolean listenerHealthy,
                         Instant lastRecovery, String lastError) { }
    private static final Logger LOG = LoggerFactory.getLogger(CacheCoordinator.class);
    private final ProductCacheClient cache;
    private final RedisAccess access;
    private String epoch = UUID.randomUUID().toString();
    private Readiness readiness = Readiness.BYPASS;
    private boolean listenerHealthy;
    private Instant lastRecovery;
    private String lastError = "Waiting for LISTEN registration and Redis health.";

    public CacheCoordinator(ProductCacheClient cache, RedisAccess access) {
        this.cache = cache;
        this.access = access;
    }

    public synchronized Status status() {
        return new Status(readiness, epoch, listenerHealthy, lastRecovery, lastError);
    }

    public synchronized boolean ready(String capturedEpoch) {
        if (readiness == Readiness.READY && !access.closed(RedisAccess.Domain.PRODUCT_CACHE)) {
            bypass("Product-cache breaker is not CLOSED; recovery health probes required.");
        }
        return readiness == Readiness.READY && listenerHealthy && epoch.equals(capturedEpoch);
    }

    public synchronized void bypass(String reason) {
        if (readiness != Readiness.BYPASS || !reason.equals(lastError)) {
            LOG.warn("Product cache bypass: {}", reason);
        }
        readiness = Readiness.BYPASS;
        lastError = reason;
    }

    public synchronized void listenerConnected() {
        listenerHealthy = true;
        bypass("LISTEN registered; a new epoch is required.");
    }

    public synchronized void listenerDisconnected(String reason) {
        listenerHealthy = false;
        bypass(reason);
    }

    // Only the owned listener loop invokes recovery, never a breaker callback.
    public synchronized void tick() {
        if (!listenerHealthy) {
            return;
        }
        try {
            if (readiness != Readiness.READY) {
                readiness = Readiness.RECOVERING;
            }
            access.ping(RedisAccess.Domain.PRODUCT_CACHE);
            if (!access.closed(RedisAccess.Domain.PRODUCT_CACHE)) {
                readiness = Readiness.RECOVERING;
                lastError = "Awaiting product-cache half-open health probes.";
                return;
            }
            if (readiness != Readiness.READY) {
                epoch = UUID.randomUUID().toString();
                lastRecovery = Instant.now();
                lastError = null;
                readiness = Readiness.READY;
            }
        } catch (RedisAccess.Unavailable failure) {
            bypass(failure.getMessage());
        }
    }

    public synchronized WriteOutcome publish(String capturedEpoch, Supplier<WriteOutcome> fill) {
        if (!ready(capturedEpoch)) {
            return WriteOutcome.REJECTED_GENERATION;
        }
        try {
            return fill.get();
        } catch (RedisAccess.Unavailable failure) {
            bypass(failure.getMessage());
            return WriteOutcome.FAILED;
        }
    }

    public synchronized boolean invalidate(long id) {
        if (!ready(epoch)) {
            return false;
        }
        try {
            cache.invalidate(epoch, id);
            return true;
        } catch (RedisAccess.Unavailable failure) {
            bypass("Invalidation outcome unknown; " + failure.getMessage());
            return false;
        }
    }

    public synchronized Status rotate() {
        epoch = UUID.randomUUID().toString();
        return status();
    }
}
