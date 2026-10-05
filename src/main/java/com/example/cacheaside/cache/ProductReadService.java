package com.example.cacheaside.cache;

import com.example.cacheaside.product.ProductRead;
import com.example.cacheaside.product.ProductRead.Source;
import com.example.cacheaside.product.ProductRead.WriteOutcome;
import com.example.cacheaside.product.ProductService;
import com.example.cacheaside.product.ProductView;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.LongAdder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Service;

@Service
public class ProductReadService {
    private final ProductService products;
    private final ProductCacheClient cache;
    private final CacheCoordinator coordinator;
    private final CacheProbe probe;
    private final LongAdder databaseLoads = new LongAdder();
    private final LongAdder hits = new LongAdder();

    public ProductReadService(ProductService products, ProductCacheClient cache, CacheCoordinator coordinator,
                              ObjectProvider<CacheProbe> probes, Environment environment) {
        this.products = products;
        this.cache = cache;
        this.coordinator = coordinator;
        probe = environment.acceptsProfiles(Profiles.of("test"))
                ? probes.getIfAvailable(() -> new CacheProbe() { }) : new CacheProbe() { };
    }

    public ProductRead read(long id) {
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
        ProductCacheClient.Capture capture;
        try {
            capture = cache.capture(epoch, id);
        } catch (RedisAccess.Unavailable failure) {
            coordinator.bypass(failure.getMessage());
            return fallback(id, started, flow);
        }
        ProductView value = load(id);
        probe.afterLoad(id, value);
        var write = coordinator.publish(epoch, () -> cache.fill(capture, CacheEntry.of(value), null));
        flow.add("Read product " + id + " from PostgreSQL; fenced publication: " + write);
        return result(started, Source.DATABASE, write, flow, value);
    }

    private ProductRead fallback(long id, long started, List<String> flow) {
        flow.add("Uncached PostgreSQL fallback for product " + id + "; no Redis fill.");
        return result(started, Source.DATABASE_FALLBACK, WriteOutcome.SKIPPED_UNAVAILABLE, flow, load(id));
    }

    private ProductView load(long id) {
        databaseLoads.increment();
        return products.find(id).orElse(null);
    }

    private ProductRead result(long started, Source source, WriteOutcome outcome, List<String> flow, ProductView value) {
        return new ProductRead(source, (System.nanoTime() - started) / 1_000_000.0, outcome, List.copyOf(flow), value);
    }

    public java.util.Map<String, Long> metrics() {
        return java.util.Map.of("databaseLoads", databaseLoads.sum(), "cacheHits", hits.sum());
    }
}
