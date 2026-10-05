package com.example.cacheaside.purchase;

import com.example.cacheaside.web.ApiException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PurchaseRequestTest {
    private final JsonMapper json = new JsonMapper();

    @Test
    void canonicalizesDefaultQuantityAndResolvedStrategyWithoutStoringRawKey() {
        var implicit = PurchaseRequest.parse(7, null, null, "buyer", "request-123");
        var explicit = PurchaseRequest.parse(7, json.readTree("{\"quantity\":1}"),
                "atomic_sql", "buyer", "request-123");
        assertThat(implicit).isEqualTo(explicit);
        assertThat(implicit.keyHash()).hasSize(64).doesNotContain("request-123");
        assertThat(PurchaseRequest.parse(8, null, null, "buyer", "request-123").fingerprint())
                .isNotEqualTo(implicit.fingerprint());
    }

    @Test
    void invalidQuantitiesNeverBecomeDefaultPurchases() {
        for (String invalid : new String[]{"null", "{}", "{\"quantity\":null}", "{\"quantity\":0}",
                "{\"quantity\":-1}", "{\"quantity\":1001}", "{\"quantity\":1.1}",
                "{\"quantity\":\"1\"}", "{\"quantity\":1,\"extra\":true}"}) {
            assertThatThrownBy(() -> PurchaseRequest.parse(1, json.readTree(invalid), null, null, "key"))
                    .as(invalid).isInstanceOf(ApiException.class);
        }
    }

    @Test
    void requiresBoundedKeyAndKnownStrategy() {
        for (String invalid : new String[]{"", "a b", "x".repeat(129), "\u00e9", null}) {
            assertThatThrownBy(() -> PurchaseRequest.parse(1, null, null, null, invalid))
                    .isInstanceOf(ApiException.class);
        }

        assertThatThrownBy(() -> PurchaseRequest.parse(1, null, "NONE", null, "key"))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> PurchaseRequest.parse(0, null, null, null, "key"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void strategyIsCanonicalAndPartOfTheFingerprint() {
        var optimistic = PurchaseRequest.parse(1, null, "optimistic", null, "key");
        assertThat(optimistic.strategy()).isEqualTo("OPTIMISTIC");
        assertThat(optimistic.fingerprint()).isEqualTo(
                PurchaseRequest.parse(1, null, "OPTIMISTIC", null, "key").fingerprint());
        assertThat(optimistic.fingerprint()).isNotEqualTo(
                PurchaseRequest.parse(1, null, "PESSIMISTIC", null, "key").fingerprint());
    }
}
