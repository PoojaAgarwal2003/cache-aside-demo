package com.example.cacheaside.product;

import com.example.cacheaside.web.ApiException;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductInputTest {
    private final JsonMapper json = new JsonMapper();
    private final ProductView original = new ProductView(1, "Laptop", new BigDecimal("1299.00"),
            10, 0, Instant.now());

    @Test
    void partialUpdatePreservesAbsentFields() {
        var patch = ProductInput.parse(json.readTree("{\"stock\":7}"), original);
        assertThat(patch.name()).isEqualTo("Laptop");
        assertThat(patch.price()).isEqualByComparingTo("1299.00");
        assertThat(patch.stock()).isEqualTo(7);
    }

    @Test
    void rejectsNullCoercionUnknownAndOversizedInteger() {
        for (String invalid : new String[]{"null", "{}", "{\"stock\":null}",
                "{\"stock\":\"7\"}", "{\"stock\":1.2}", "{\"stock\":999999999999}",
                "{\"version\":1}", "{\"price\":\"1.00\"}"}) {
            assertThatThrownBy(() -> ProductInput.parse(json.readTree(invalid), original))
                    .as(invalid).isInstanceOf(ApiException.class);
        }
    }

    @Test
    void creationRequiresAllFields() {
        assertThatThrownBy(() -> ProductInput.parse(json.readTree("{\"stock\":7}"), null))
                .isInstanceOf(ApiException.class);
    }
}
