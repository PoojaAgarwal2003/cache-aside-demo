package com.example.cacheaside.purchase;

import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
public class PurchaseController {
    private final PurchaseService service;

    public PurchaseController(PurchaseService service) {
        this.service = service;
    }

    @PostMapping("/products/{id}/purchase")
    public ResponseEntity<PurchaseResponse> purchase(
            @PathVariable long id,
            @RequestBody(required = false) JsonNode body,
            @RequestParam(required = false) String strategy,
            @RequestHeader(name = "X-Client-Id", required = false) String client,
            @RequestHeader(name = "Idempotency-Key", required = false) String key) {
        long started = System.nanoTime();
        var request = PurchaseRequest.parse(id, body, strategy, client, key);
        UUID requestId = UUID.fromString(MDC.get("requestId"));
        var result = service.purchase(request, requestId);
        var response = new PurchaseResponse(result.outcome().name(), result.outcome().message(),
                false, result.strategy(), PurchaseStrategy.resolve(result.strategy()).meaning(),
                result.productId(), result.quantity(), result.stockLeft(), result.version(),
                result.attempts(), requestId, result.originalRequestId(), result.purchaseId(),
                (System.nanoTime() - started) / 1_000_000.0, result.replayed());
        return ResponseEntity.status(result.outcome().status())
                .header("X-Purchase-Result", result.outcome().name())
                .header("Idempotency-Replayed", Boolean.toString(result.replayed()))
                .body(response);
    }

    public record PurchaseResponse(String code, String message, boolean retryable, String strategy,
                                   String meaning, long productId, int quantity, Integer stockLeft,
                                   Long version, int attempts, UUID requestId, UUID originalRequestId,
                                   UUID purchaseId, double durationMs, boolean replayed) {
    }
}
