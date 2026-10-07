package com.example.cacheaside.web;

import java.util.List;
import java.util.Map;
import com.example.cacheaside.cache.CacheCoordinator;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class StatusController {
    private final JdbcTemplate jdbc;
    private final LabProperties properties;
    private final CacheCoordinator cache;
    private final Environment environment;

    public StatusController(JdbcTemplate jdbc, LabProperties properties, CacheCoordinator cache, Environment environment) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.cache = cache;
        this.environment = environment;
    }

    @GetMapping("/status")
    public Status status() {
        jdbc.queryForObject("SELECT 1", Integer.class);
        return new Status("FlashSale Lab", 5, "AVAILABLE", cache.status().readiness().name(),
                properties.demoEnabled(), properties.readDelayMs(), properties.purchaseDelayMs(),
                List.of("PRODUCT_API", "ATOMIC_SQL", "PESSIMISTIC", "OPTIMISTIC",
                        "DEMO_ONLY_UNSAFE_NONE", "REDIS_ASSISTED", "PERSISTED_IDEMPOTENCY", "ADMISSION_RECONCILIATION",
                        "GENERATION_FENCED_CACHE", "INVALIDATE_ONLY_LISTENER", "BOUNDED_REBUILD_LEASES",
                        "INDEPENDENT_REDIS_BREAKERS", "SLIDING_WINDOW_RATE_LIMIT", "PERSISTED_HTTP_EXPERIMENTS",
                        "DRAINED_LEDGER_ACCOUNTING", "GUIDED_SCENARIOS", "CURSOR_EVENTS_AND_EXPORT",
                        "LOCAL_DASHBOARD", "GUIDED_VIEWS", "SHOWCASE_LAYOUT", "BRUNO_COLLECTION", "REPEATED_BENCHMARKS"),
                List.of(environment.getActiveProfiles()),
                Map.of("java", System.getProperty("java.version"), "vm", System.getProperty("java.vm.name"),
                        "availableProcessors", Runtime.getRuntime().availableProcessors(),
                        "maxHeapBytes", Runtime.getRuntime().maxMemory(),
                        "postgres", jdbc.queryForObject("SHOW server_version", String.class)));
    }

    public record Status(String application, int milestone, String database, String cache,
                         boolean mutationsEnabled, int readDelayMs, int purchaseDelayMs,
                         List<String> capabilities, List<String> profiles, Map<String, Object> runtime) {
    }
}
