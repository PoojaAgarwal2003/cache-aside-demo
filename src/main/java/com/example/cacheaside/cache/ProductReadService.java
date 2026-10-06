package com.example.cacheaside.cache;

import com.example.cacheaside.product.ProductRead;
import com.example.cacheaside.product.ProductRead.Source;
import com.example.cacheaside.product.ProductRead.WriteOutcome;
import com.example.cacheaside.product.ProductService;
import com.example.cacheaside.product.ProductView;
import com.example.cacheaside.web.ApiException;
import com.example.cacheaside.web.LabProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class ProductReadService {
    private static final Logger LOG = LoggerFactory.getLogger(ProductReadService.class);
    private final ProductService products;
    private final ProductCacheClient cache;
    private final CacheCoordinator coordinator;
    private final CacheProbe probe;
    private final LongAdder databaseLoads = new LongAdder();
    private final LongAdder hits = new LongAdder();
    private final LongAdder waits = new LongAdder();
    private final LongAdder overloads = new LongAdder();
    private final CacheReadProperties properties;
    private final LabProperties lab;
    private final Semaphore database;
    private final com.example.cacheaside.demo.RunGuard runs;
    private final com.example.cacheaside.demo.DatabaseWork work;
    private final com.example.cacheaside.demo.RunHooks hooks;

    public ProductReadService(ProductService products, ProductCacheClient cache, CacheCoordinator coordinator,
                              ObjectProvider<CacheProbe> probes, Environment environment,
                              CacheReadProperties properties, LabProperties lab, com.example.cacheaside.demo.RunGuard runs,
                              com.example.cacheaside.demo.DatabaseWork work, com.example.cacheaside.demo.RunHooks hooks) {
        this.products = products;
        this.cache = cache;
        this.coordinator = coordinator;
        this.properties = properties;
        this.lab = lab;
        this.runs = runs;
        this.work = work;
        this.hooks = hooks;
        database = new Semaphore(properties.databasePermits(), true);
        probe = environment.acceptsProfiles(Profiles.of("test"))
                ? probes.getIfAvailable(() -> new CacheProbe() { }) : new CacheProbe() { };
    }

    public ProductRead read(long id) {
        return read(id, true);
    }

    public ProductRead read(long id, boolean protection) {
        runs.check(id);
        runs.checkDeadline();
        if (!protection && !lab.demoEnabled()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "DEMO_DISABLED",
                    "Disabling stampede protection requires demo mode.", false);
        }
        long started = System.nanoTime();
        var flow = new ArrayList<String>();
        String epoch = coordinator.status().epoch();
        if (!coordinator.ready(epoch)) {
            flow.add("Product cache is bypassed until listener and Redis recovery complete.");
            return fallback(id, started, flow);
        }
        var lookup = cache.lookup(epoch, id);
        flow.add("Product " + id + " cache lookup: " + lookup.kind());
        if (lookup.kind() == ProductCacheClient.LookupKind.UNAVAILABLE) {
            coordinator.bypass(lookup.error());
            return fallback(id, started, flow);
        }
        if (lookup.hit() && coordinator.ready(epoch)) {
            hits.increment();
            return result(started, Source.REDIS_CACHE, WriteOutcome.NOT_ATTEMPTED, flow, lookup.entry().data());
        }
        String owner = null;
        try {
            if (protection) {
                owner = cache.acquire(epoch, id);
                if (owner == null) {
                    return waitForOwner(id, epoch, started, flow);
                }
                var recheck = cache.lookup(epoch, id);
                if (recheck.kind() == ProductCacheClient.LookupKind.UNAVAILABLE) {
                    throw new RedisAccess.Unavailable(RedisAccess.Domain.PRODUCT_CACHE, recheck.error());
                }
                if (recheck.hit() && coordinator.ready(epoch)) {
                    hits.increment();
                    flow.add("Lease acquired; recheck found an already-published value.");
                    return result(started, Source.REDIS_CACHE, WriteOutcome.NOT_ATTEMPTED, flow, recheck.entry().data());
                }
            }
            var capture = cache.capture(epoch, id);
            ProductView value = load(id);
            String token = owner;
            var write = coordinator.publish(epoch, () -> {
                var outcome = cache.fill(capture, CacheEntry.of(value), token);
                probe.afterFill(id);
                return outcome;
            });
            flow.add("Read product " + id + " from PostgreSQL; fenced publication: " + write);
            return result(started, Source.DATABASE, write, flow, value);
        } catch (RedisAccess.Unavailable failure) {
            coordinator.bypass(failure.getMessage());
            return fallback(id, started, flow);
        } finally {
            if (owner != null) {
                try {
                    cache.release(epoch, id, owner);
                } catch (RedisAccess.Unavailable failure) {
                    coordinator.bypass("Lease cleanup unavailable; " + failure.getMessage());
                }
            }
        }
    }

    private ProductRead waitForOwner(long id, String epoch, long started, List<String> flow) {
        waits.increment();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(properties.waiterMs());
        flow.add("Another loader owns the lease; bounded cache wait.");
        while (System.nanoTime() < deadline && coordinator.ready(epoch)) {
            ProductService.delay(properties.pollMs());
            var lookup = cache.lookup(epoch, id);
            if (lookup.kind() == ProductCacheClient.LookupKind.UNAVAILABLE) {
                coordinator.bypass(lookup.error());
                break;
            }
            if (lookup.hit() && coordinator.ready(epoch)) {
                hits.increment();
                return result(started, Source.REDIS_CACHE_AFTER_WAIT, WriteOutcome.NOT_ATTEMPTED, flow, lookup.entry().data());
            }
        }
        flow.add("Wait deadline/readiness ended; waiter cannot publish without a lease.");
        return result(started, Source.DATABASE_FALLBACK, WriteOutcome.NOT_ATTEMPTED, flow, fallbackLoad(id));
    }

    private ProductRead fallback(long id, long started, List<String> flow) {
        flow.add("Uncached PostgreSQL fallback for product " + id + "; no Redis fill.");
        return result(started, Source.DATABASE_FALLBACK, WriteOutcome.SKIPPED_UNAVAILABLE, flow, fallbackLoad(id));
    }

    private ProductView fallbackLoad(long id) {
        try (var scope = work.purpose(com.example.cacheaside.demo.DatabaseWork.Purpose.FALLBACK)) {
            return load(id);
        }
    }

    private ProductView load(long id) {
        boolean acquired = false;
        try {
            acquired = database.tryAcquire(properties.databaseWaitMs(), TimeUnit.MILLISECONDS);
            if (!acquired) {
                overloads.increment();
                throw ApiException.unavailable("DATABASE_OVERLOADED", "Product read capacity exhausted; retry later.");
            }
            databaseLoads.increment();
            ProductView value = products.find(id).orElse(null);
            hooks.afterLoad(id);
            probe.afterLoad(id, value);
            return value;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw ApiException.unavailable("INTERRUPTED", "Product read wait interrupted.");
        } finally {
            if (acquired) { database.release(); }
        }
    }

    private ProductRead result(long started, Source source, WriteOutcome outcome, List<String> flow, ProductView value) {
        double durationMs = (System.nanoTime() - started) / 1_000_000.0;
        LOG.info("Product read source={} cacheWrite={} found={} durationMs={}",
                source, outcome, value != null, durationMs);
        return new ProductRead(source, durationMs, outcome, List.copyOf(flow), value);
    }

    public java.util.Map<String, Long> metrics() {
        return java.util.Map.of("databaseLoads", databaseLoads.sum(), "cacheHits", hits.sum(),
                "waiters", waits.sum(), "overloads", overloads.sum(),
                "databasePermitsAvailable", (long) database.availablePermits());
    }
}
