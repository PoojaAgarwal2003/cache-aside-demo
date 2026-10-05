package com.example.cacheaside.cache;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("lab.cache")
public record CacheReadProperties(
        @DefaultValue("8") @Min(1) @Max(16) int databasePermits,
        @DefaultValue("250") @Min(0) @Max(1000) int databaseWaitMs,
        @DefaultValue("3000") @Min(50) @Max(3000) int waiterMs,
        @DefaultValue("50") @Min(10) @Max(100) int pollMs) { }
