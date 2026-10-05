package com.example.cacheaside.cache;

import com.example.cacheaside.web.ApiException;
import com.example.cacheaside.web.LabProperties;
import com.example.cacheaside.ratelimit.RateLimiter;
import jakarta.validation.constraints.Positive;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/cache")
public class CacheController {
    private final CacheCoordinator coordinator;
    private final ProductCacheClient cache;
    private final ProductReadService reads;
    private final DbChangeListener listener;
    private final LabProperties properties;
    private final RedisAccess access;
    private final RateLimiter limiter;

    public CacheController(CacheCoordinator coordinator, ProductCacheClient cache, ProductReadService reads,
                           DbChangeListener listener, LabProperties properties, RedisAccess access, RateLimiter limiter) {
        this.coordinator = coordinator;
        this.cache = cache;
        this.reads = reads;
        this.listener = listener;
        this.properties = properties;
        this.access = access;
        this.limiter = limiter;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        requireDemo();
        return Map.of("productCache", coordinator.status(), "listener", listener.health(), "metrics", reads.metrics(),
                "breakers", access.status(),
                "rateLimit", limiter.status(),
                "explanation", "Eventual single-instance cache. LISTEN is not durable CDC; only read paths fill.");
    }

    @GetMapping("/products/{id}")
    public ProductCacheClient.Inspection inspect(@PathVariable @Positive long id) {
        requireDemo();
        return cache.inspect(coordinator.status().epoch(), id);
    }

    @DeleteMapping("/products/{id}")
    public Map<String, String> invalidate(@PathVariable @Positive long id) {
        requireDemo();
        if (!coordinator.invalidate(id)) {
            throw ApiException.unavailable("CACHE_BYPASSED", "Invalidation is not confirmed; product cache is bypassed.");
        }
        return Map.of("result", "INVALIDATED", "productId", Long.toString(id));
    }

    @DeleteMapping("/products")
    public Map<String, Object> clear() {
        requireDemo();
        return Map.of("result", "INVALIDATED_NAMESPACE", "productCache", coordinator.rotate());
    }

    private void requireDemo() {
        if (!properties.demoEnabled()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "DEMO_DISABLED", "Cache diagnostics require demo mode.", false);
        }
    }
}
