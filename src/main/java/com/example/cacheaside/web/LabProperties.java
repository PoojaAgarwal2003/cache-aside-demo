package com.example.cacheaside.web;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("lab")
public record LabProperties(
        boolean demoEnabled,
        @Min(0) @Max(2000) int readDelayMs,
        @Min(0) @Max(1000) int purchaseDelayMs) {
}
