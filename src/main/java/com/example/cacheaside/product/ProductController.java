package com.example.cacheaside.product;

import com.example.cacheaside.purchase.FixtureActivity;
import jakarta.validation.constraints.Positive;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/products")
public class ProductController {
    private final ProductService service;
    private final FixtureActivity activity;

    public ProductController(ProductService service, FixtureActivity activity) {
        this.service = service;
        this.activity = activity;
    }

    @GetMapping("/{id}")
    public ResponseEntity<ProductRead> get(@PathVariable @Positive long id) {
        long started = System.nanoTime();
        var product = service.find(id);
        var result = new ProductRead(ProductRead.Source.DATABASE,
                (System.nanoTime() - started) / 1_000_000.0,
                ProductRead.WriteOutcome.SKIPPED_UNAVAILABLE,
                List.of("Product cache is not implemented; no Redis read-cache lookup or fill attempted.",
                        "Read product " + id + " from PostgreSQL.",
                        product.isPresent() ? "Found." : "Not found."),
                product.orElse(null));
        return ResponseEntity.status(product.isPresent() ? 200 : 404)
                .header("X-Cache", "MISS").body(result);
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
