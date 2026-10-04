package com.example.cacheaside.product;

import com.example.cacheaside.web.ApiException;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.Set;
import tools.jackson.databind.JsonNode;

public record ProductInput(
        @NotBlank @Size(max = 255) String name,
        @NotNull @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal price,
        @NotNull @Min(0) @Max(1_000_000) Integer stock) {

    public static ProductInput parse(JsonNode body, ProductView previous) {
        if (!body.isObject() || body.isEmpty()
                || !Set.of("name", "price", "stock").containsAll(body.propertyNames())) {
            throw ApiException.invalid("Use a nonempty object containing only name, price and stock.");
        }
        if (previous == null && !(body.has("name") && body.has("price") && body.has("stock"))) {
            throw ApiException.invalid("name, price and stock are required.");
        }
        if (body.has("name") && !body.get("name").isString()
                || body.has("price") && !body.get("price").isNumber()
                || body.has("stock") && (!body.get("stock").isIntegralNumber()
                || !body.get("stock").canConvertToInt())) {
            throw ApiException.invalid("Fields must have the documented JSON types; null is not valid.");
        }
        return new ProductInput(
                body.has("name") ? body.get("name").asString() : previous.name(),
                body.has("price") ? body.get("price").decimalValue() : previous.price(),
                body.has("stock") ? body.get("stock").intValue() : previous.stock());
    }
}
