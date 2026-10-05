package com.example.cacheaside.product;

import java.util.List;

public record ProductRead(Source source, double responseTimeMs, WriteOutcome cacheWriteOutcome,
                          List<String> flow, ProductView data) {
    public enum Source { REDIS_CACHE, REDIS_CACHE_AFTER_WAIT, DATABASE, DATABASE_FALLBACK }
    public enum WriteOutcome {
        STORED, SKIPPED_UNAVAILABLE, REJECTED_GENERATION, REJECTED_LOCK, FAILED, NOT_ATTEMPTED
    }
}
