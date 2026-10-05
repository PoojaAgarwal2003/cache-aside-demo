package com.example.cacheaside.cache;

import com.example.cacheaside.product.ProductView;

/** Controlled boundaries; implementations are accepted only under the test profile. */
public interface CacheProbe {
    default void afterLoad(long id, ProductView value) { }
    default void beforeListen() { }
}
