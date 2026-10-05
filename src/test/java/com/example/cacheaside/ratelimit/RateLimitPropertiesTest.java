package com.example.cacheaside.ratelimit;

import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitPropertiesTest {
    @Test
    void limitsAndWindowsHaveExplicitLowerAndUpperBounds() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertThat(validator.validate(new RateLimitProperties(true, 10, 10_000))).isEmpty();
            assertThat(validator.validate(new RateLimitProperties(false, 1, 1_000))).isEmpty();
            assertThat(validator.validate(new RateLimitProperties(true, 1_000, 60_000))).isEmpty();
            assertThat(validator.validate(new RateLimitProperties(true, 0, 999))).hasSize(2);
            assertThat(validator.validate(new RateLimitProperties(true, 1_001, 60_001))).hasSize(2);
        }
    }
}
