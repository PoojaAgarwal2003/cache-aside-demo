package com.example.cacheaside.purchase;

import java.util.UUID;

/** Test-only boundary observer; production has no implementation or HTTP fault hook. */
public interface PurchaseProbe {
    default void afterRead(PurchaseRequest request, int stock, long version, int attempt) { }
    default void afterCommit(PurchaseRequest request, UUID purchaseId) { }
}
