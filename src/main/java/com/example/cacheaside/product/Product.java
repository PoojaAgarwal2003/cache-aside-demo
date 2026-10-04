package com.example.cacheaside.product;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "products")
public class Product {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal price;

    @Column(nullable = false)
    private int stock;

    @Version
    private long version;

    @Column(name = "updated_at", insertable = false, updatable = false, nullable = false)
    private Instant updatedAt;

    protected Product() {
    }

    public Product(ProductInput input) {
        update(input);
    }

    public void update(ProductInput input) {
        name = input.name();
        price = input.price();
        stock = input.stock();
    }

    public ProductView view() {
        return new ProductView(id, name, price, stock, version, updatedAt);
    }
}
