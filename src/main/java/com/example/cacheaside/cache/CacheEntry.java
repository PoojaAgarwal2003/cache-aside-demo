package com.example.cacheaside.cache;

import com.example.cacheaside.product.ProductView;

public record CacheEntry(int schemaVersion, Kind kind, ProductView data) {
    public enum Kind { PRESENT, ABSENT }

    public static CacheEntry of(ProductView product) {
        return new CacheEntry(1, product == null ? Kind.ABSENT : Kind.PRESENT, product);
    }

    public boolean validFor(long id) {
        if (schemaVersion != 1 || kind == null) {
            return false;
        }
        if (kind == Kind.ABSENT) {
            return data == null;
        }
        return data != null && data.id() == id && data.version() >= 0 && data.updatedAt() != null
                && data.name() != null && !data.name().isBlank() && data.name().length() <= 255
                && data.price() != null && data.price().signum() >= 0 && data.price().scale() <= 2
                && data.price().precision() <= 12;
    }
}
