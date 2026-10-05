package com.example.cacheaside.web;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class StatusController {
    private final JdbcTemplate jdbc;
    private final LabProperties properties;

    public StatusController(JdbcTemplate jdbc, LabProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    @GetMapping("/status")
    public Status status() {
        jdbc.queryForObject("SELECT 1", Integer.class);
        return new Status("FlashSale Lab", 1, "AVAILABLE", "NOT_IMPLEMENTED",
                properties.demoEnabled(), properties.readDelayMs(), properties.purchaseDelayMs(),
                List.of("PRODUCT_API", "ATOMIC_SQL", "PESSIMISTIC", "OPTIMISTIC",
                        "DEMO_ONLY_UNSAFE_NONE", "PERSISTED_IDEMPOTENCY"));
    }

    public record Status(String application, int milestone, String database, String cache,
                         boolean mutationsEnabled, int readDelayMs, int purchaseDelayMs,
                         List<String> capabilities) {
    }
}
