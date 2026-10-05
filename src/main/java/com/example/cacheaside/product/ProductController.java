package com.example.cacheaside.product;

import com.example.cacheaside.purchase.FixtureActivity;
import com.example.cacheaside.cache.ProductReadService;
import jakarta.validation.constraints.Positive;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/products")
public class ProductController {
    private final ProductService service;
    private final FixtureActivity activity;
    private final ProductReadService reads;

    public ProductController(ProductService service, FixtureActivity activity, ProductReadService reads) {
        this.service = service;
        this.activity = activity;
        this.reads = reads;
    }

    @GetMapping("/{id}")
    public ResponseEntity<ProductRead> get(@PathVariable @Positive long id,
                                         @RequestParam(defaultValue = "true") boolean stampedeProtection) {
        var result = reads.read(id, stampedeProtection);
        String header = switch (result.source()) {
            case REDIS_CACHE, REDIS_CACHE_AFTER_WAIT -> "HIT";
            case DATABASE -> "MISS";
            case DATABASE_FALLBACK -> "BYPASS";
        };
        return ResponseEntity.status(result.data() == null ? 404 : 200).header("X-Cache", header).body(result);
    }

    @PostMapping
    public ResponseEntity<ProductView> create(@RequestBody JsonNode body) {
        var product = service.create(body);
        return ResponseEntity.created(URI.create("/products/" + product.id())).body(product);
    }

    @RequestMapping(path = "/{id}", method = {RequestMethod.PATCH, RequestMethod.PUT})
    public ProductView update(@PathVariable @Positive long id, @RequestBody JsonNode body) {
        return activity.maintenance(id, () -> service.update(id, body));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable @Positive long id) {
        return activity.maintenance(id, () -> {
            service.delete(id);
            return ResponseEntity.noContent().build();
        });
    }
}
