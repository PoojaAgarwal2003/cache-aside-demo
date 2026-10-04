package com.example.cacheaside.product;

import java.util.List;

public record ProductRead(Source source, double responseTimeMs, WriteOutcome cacheWriteOutcome,
                          List<String> flow, ProductView data) {
    public enum Source { DATABASE }
    public enum WriteOutcome { SKIPPED_UNAVAILABLE }
}
