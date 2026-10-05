package com.example.cacheaside.cache;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class CacheInvalidation {
    private final CacheCoordinator coordinator;

    public CacheInvalidation(CacheCoordinator coordinator) { this.coordinator = coordinator; }

    public void afterCommit(long id) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Invalidation must be registered inside the database transaction.");
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { coordinator.invalidate(id); }
            @Override public void afterCompletion(int status) {
                if (status == STATUS_UNKNOWN) {
                    coordinator.bypass("Database commit acknowledgement unknown.");
                }
            }
        });
    }
}
