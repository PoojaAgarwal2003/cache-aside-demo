package com.example.cacheaside.web;

import java.util.List;
import com.example.cacheaside.cache.CacheCoordinator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class StatusController {
    private final JdbcTemplate jdbc;
    private final LabProperties properties;
    private final CacheCoordinator cache;

    public StatusController(JdbcTemplate jdbc, LabProperties properties, CacheCoordinator cache) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.cache = cache;
    }

    @GetMapping("/status")
    public Status status() {
        jdbc.queryForObject("SELECT 1", Integer.class);
        return new Status("FlashSale Lab", 3, "AVAILABLE", cache.status().readiness().name(),
                properties.demoEnabled(), properties.readDelayMs(), properties.purchaseDelayMs(),
                List.of("PRODUCT_API", "ATOMIC_SQL", "PESSIMISTIC", "OPTIMISTIC",
                        "DEMO_ONLY_UNSAFE_NONE", "REDIS_ASSISTED", "PERSISTED_IDEMPOTENCY", "ADMISSION_RECONCILIATION",
                        "GENERATION_FENCED_CACHE", "INVALIDATE_ONLY_LISTENER", "BOUNDED_REBUILD_LEASES",
                        "INDEPENDENT_REDIS_BREAKERS", "SLIDING_WINDOW_RATE_LIMIT"));
    }

    public record Status(String application, int milestone, String database, String cache,
                         boolean mutationsEnabled, int readDelayMs, int purchaseDelayMs,
                         List<String> capabilities) {
    }
}
