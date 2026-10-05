package com.example.cacheaside.purchase;

import java.util.UUID;

public record PurchaseDecision(Outcome outcome, String strategy, long productId, int quantity,
                               Integer stockLeft, Long version, int attempts,
                               UUID purchaseId, UUID originalRequestId, boolean replayed) {
    public enum Outcome {
        SOLD(200, "Inventory decrement and ledger entry committed."),
        OUT_OF_STOCK(409, "Insufficient stock at the database decision."),
        GAVE_UP(409, "Optimistic conflicts exhausted the bounded attempts; stock may remain."),
        ADMISSION_REJECTED(409, "Redis declined admission; this does not prove database stock is zero."),
        NOT_FOUND(404, "Product not found at the database decision.");

        private final int status;
        private final String message;

        Outcome(int status, String message) {
            this.status = status;
            this.message = message;
        }

        public int status() { return status; }
        public String message() { return message; }
    }
}
