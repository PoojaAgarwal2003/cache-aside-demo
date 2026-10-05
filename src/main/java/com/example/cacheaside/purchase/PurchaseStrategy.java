package com.example.cacheaside.purchase;

import com.example.cacheaside.web.ApiException;
import java.util.Locale;

public enum PurchaseStrategy {
    ATOMIC_SQL("PostgreSQL conditional UPDATE is the inventory authority."),
    PESSIMISTIC("PostgreSQL row lock covers the stock check, work and decrement."),
    OPTIMISTIC("A fresh transaction retries a stock/version conflict, at most 20 attempts.");

    private final String meaning;

    PurchaseStrategy(String meaning) {
        this.meaning = meaning;
    }

    public String meaning() {
        return meaning;
    }

    public static PurchaseStrategy resolve(String value) {
        if (value == null) {
            return ATOMIC_SQL;
        }
        try {
            return valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException invalid) {
            throw ApiException.invalid("Unknown or not yet implemented purchase strategy.");
        }
    }
}
