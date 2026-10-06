package com.example.cacheaside.demo;

import com.example.cacheaside.purchase.PurchaseStrategy;
import com.example.cacheaside.web.ApiException;
import java.util.Set;
import tools.jackson.databind.JsonNode;

public record RunParameters(Scenario scenario, String strategy, int buyers, int concurrency,
                            int stock, int quantity, long seed, int jitterMs, int durationSeconds,
                            boolean stampedeProtection) {
    public enum Scenario { PURCHASE, READ, COMPARE, COLD_WARM, STAMPEDE, STALE_FILL, OUTAGE, LOST_RESPONSE }
    private static final Set<String> FIELDS = Set.of("scenario", "strategy", "buyers", "concurrency", "stock",
            "quantity", "seed", "jitterMs", "durationSeconds", "stampedeProtection");

    public static RunParameters parse(JsonNode body) {
        if (body == null || !body.isObject() || body.propertyNames().stream().anyMatch(key -> !FIELDS.contains(key))) {
            throw ApiException.invalid("Use a run parameter object with documented fields.");
        }
        Scenario scenario;
        try { scenario = Scenario.valueOf(text(body, "scenario", "PURCHASE")); }
        catch (IllegalArgumentException invalid) { throw ApiException.invalid("Unknown experiment scenario."); }
        String strategy = PurchaseStrategy.resolve(text(body, "strategy", "ATOMIC_SQL")).name();
        int fixedBuyers = switch (scenario) {
            case COLD_WARM -> 2;
            case STALE_FILL, OUTAGE, LOST_RESPONSE -> 1;
            default -> 0;
        };
        int buyers = number(body, "buyers", fixedBuyers > 0 ? fixedBuyers : 50, 1, 100);
        int concurrency = number(body, "concurrency", Math.min(buyers, fixedBuyers > 0 ? 1 : 10), 1, 50);
        if (fixedBuyers > 0 && (buyers != fixedBuyers || concurrency != 1)) {
            throw ApiException.invalid(scenario + " uses " + fixedBuyers + " buyer(s) and concurrency 1.");
        }
        if (concurrency > buyers) { throw ApiException.invalid("Concurrency cannot exceed buyers."); }
        long seed = 1;
        if (body.has("seed")) {
            if (!body.get("seed").isIntegralNumber() || !body.get("seed").canConvertToLong()) {
                throw ApiException.invalid("Seed must be a signed 64-bit integer.");
            }
            seed = body.get("seed").asLong();
        }
        boolean protection = true;
        if (body.has("stampedeProtection")) {
            if (!body.get("stampedeProtection").isBoolean()) { throw ApiException.invalid("Use a boolean protection flag."); }
            protection = body.get("stampedeProtection").asBoolean();
        }
        return new RunParameters(scenario, strategy, buyers, concurrency,
                number(body, "stock", 10, 0, 1_000_000), number(body, "quantity", 1, 1, 1000),
                seed, number(body, "jitterMs", 0, 0, 100),
                number(body, "durationSeconds", 60, 1, 120), protection);
    }

    public int intendedOperations() {
        return buyers * switch (scenario) { case COMPARE -> 5; case STAMPEDE -> 2; case OUTAGE -> 3; default -> 1; };
    }

    private static String text(JsonNode body, String field, String fallback) {
        if (!body.has(field)) { return fallback; }
        if (!body.get(field).isString()) { throw ApiException.invalid(field + " must be a string."); }
        return body.get(field).asString();
    }

    private static int number(JsonNode body, String field, int fallback, int min, int max) {
        if (!body.has(field)) { return fallback; }
        var value = body.get(field);
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.asInt() < min || value.asInt() > max) {
            throw ApiException.invalid(field + " must be an integer in " + min + ".." + max + ".");
        }
        return value.asInt();
    }
}
