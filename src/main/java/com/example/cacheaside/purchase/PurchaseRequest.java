package com.example.cacheaside.purchase;

import com.example.cacheaside.web.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import tools.jackson.databind.JsonNode;

public record PurchaseRequest(long productId, int quantity, String strategy,
                              String clientId, String keyHash, String fingerprint) {
    public static PurchaseRequest parse(long id, JsonNode body, String strategy,
                                        String client, String key) {
        if (id <= 0) {
            throw ApiException.invalid("Product id must be positive.");
        }
        String resolved = strategy == null ? "ATOMIC_SQL" : strategy.toUpperCase(Locale.ROOT);
        if (!"ATOMIC_SQL".equals(resolved)) {
            throw ApiException.invalid("Milestone 1 implements only ATOMIC_SQL.");
        }
        String identity = client == null ? "local" : client;
        if (!identity.matches("[A-Za-z0-9._-]{1,64}")) {
            throw ApiException.invalid("X-Client-Id must be 1-64 safe ASCII characters.");
        }
        if (key == null || !key.matches("[A-Za-z0-9._:-]{1,128}")) {
            throw ApiException.invalid("Idempotency-Key is required: 1-128 ASCII letters, digits, . _ : or -.");
        }
        int quantity = 1;
        if (body != null) {
            if (!body.isObject() || body.size() != 1 || !body.has("quantity")
                    || !body.get("quantity").isIntegralNumber() || !body.get("quantity").canConvertToInt()) {
                throw ApiException.invalid("Use {\"quantity\":1}; omitted body defaults to 1, null does not.");
            }
            quantity = body.get("quantity").intValue();
        }
        if (quantity < 1 || quantity > 1000) {
            throw ApiException.invalid("Quantity must be an integer between 1 and 1000.");
        }
        return new PurchaseRequest(id, quantity, resolved, identity, sha256(key),
                sha256("v1\n" + id + "\n" + quantity + "\n" + resolved));
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("The Java runtime must support SHA-256.", impossible);
        }
    }
}
