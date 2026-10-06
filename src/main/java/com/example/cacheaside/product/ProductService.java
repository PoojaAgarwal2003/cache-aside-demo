package com.example.cacheaside.product;

import com.example.cacheaside.web.ApiException;
import com.example.cacheaside.web.LabProperties;
import com.example.cacheaside.cache.CacheInvalidation;
import jakarta.persistence.EntityManager;
import jakarta.validation.Validator;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

@Service
public class ProductService {
    private final ProductRepository repository;
    private final EntityManager entityManager;
    private final Validator validator;
    private final LabProperties properties;
    private final CacheInvalidation invalidation;
    private final com.example.cacheaside.demo.RunGuard runs;

    public ProductService(ProductRepository repository, EntityManager entityManager,
                          Validator validator, LabProperties properties, CacheInvalidation invalidation,
                          com.example.cacheaside.demo.RunGuard runs) {
        this.repository = repository;
        this.entityManager = entityManager;
        this.validator = validator;
        this.properties = properties;
        this.invalidation = invalidation;
        this.runs = runs;
    }

    @Transactional(readOnly = true, timeout = 6)
    public Optional<ProductView> find(long id) {
        runs.check(id);
        delay(properties.readDelayMs());
        return repository.findById(id).map(Product::view);
    }

    @Transactional
    public ProductView create(JsonNode body) {
        var input = validated(ProductInput.parse(body, null));
        var product = repository.saveAndFlush(new Product(input));
        entityManager.refresh(product);
        invalidation.afterCommit(product.view().id());
        return product.view();
    }

    @Transactional
    public ProductView update(long id, JsonNode body) {
        runs.check(id);
        var product = repository.findById(id).orElseThrow(ApiException::notFound);
        product.update(validated(ProductInput.parse(body, product.view())));
        repository.flush();
        // The trigger and Hibernate both use OLD.version + 1, never + 2.
        entityManager.refresh(product);
        invalidation.afterCommit(id);
        return product.view();
    }

    @Transactional
    public void delete(long id) {
        runs.check(id);
        var product = repository.findById(id).orElseThrow(ApiException::notFound);
        repository.delete(product);
        repository.flush();
        invalidation.afterCommit(id);
    }

    private ProductInput validated(ProductInput input) {
        if (!validator.validate(input).isEmpty()) {
            throw ApiException.invalid("Use a nonblank name (1-255 chars), price 0-9999999999.99 "
                    + "(at most 2 decimal places), and integer stock 0-1000000.");
        }
        return input;
    }

    public static void delay(int millis) {
        if (millis == 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw ApiException.unavailable("INTERRUPTED", "The operation was interrupted; retry safely.");
        }
    }
}
