package com.example.cacheaside.product;

import java.math.BigDecimal;
import java.time.Instant;

public record ProductView(long id, String name, BigDecimal price, int stock,
                          long version, Instant updatedAt) {
}
