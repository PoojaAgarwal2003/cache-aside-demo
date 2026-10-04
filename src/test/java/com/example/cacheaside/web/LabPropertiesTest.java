package com.example.cacheaside.web;

import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LabPropertiesTest {
    @Test
    void delaysAreBoundedAndZeroIsValid() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertThat(validator.validate(new LabProperties(false, 0, 0))).isEmpty();
            assertThat(validator.validate(new LabProperties(true, -1, 1001))).hasSize(2);
        }
    }
}
