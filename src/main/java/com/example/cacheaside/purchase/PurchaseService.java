package com.example.cacheaside.purchase;

import com.example.cacheaside.product.ProductService;
import com.example.cacheaside.cache.CacheInvalidation;
import com.example.cacheaside.web.ApiException;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class PurchaseService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final InventoryStrategies strategies;
    private final PurchaseFixturePolicy fixtures;
    private final FixtureActivity activity;
    private final StockAdmissionService admission;
    private final CacheInvalidation invalidation;

    public PurchaseService(JdbcTemplate jdbc, PlatformTransactionManager manager, InventoryStrategies strategies,
                           PurchaseFixturePolicy fixtures, FixtureActivity activity, StockAdmissionService admission,
                           CacheInvalidation invalidation) {
        this.jdbc = jdbc;
        this.strategies = strategies;
        this.fixtures = fixtures;
        this.activity = activity;
        this.admission = admission;
        this.invalidation = invalidation;
        transaction = new TransactionTemplate(manager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setTimeout(8);
    }

    public PurchaseDecision purchase(PurchaseRequest request, UUID requestId) {
        return activity.purchase(request.productId(), () -> {
            if ("REDIS_ASSISTED".equals(request.strategy())) {
                return admission.admittedWork(() -> {
                    try {
                        return purchaseAttempts(request, requestId);
                    } finally {
                        admission.settleRequest(request);
                    }
                });
            }
            return purchaseAttempts(request, requestId);
        });
    }

    private PurchaseDecision purchaseAttempts(PurchaseRequest request, UUID requestId) {
        fixtures.prepare(request);
        // execute() returns only after commit. Unknown commit failures escape as
        // retryable errors; a retry resolves the persisted key, never refunds.
        for (int attempt = 1; attempt <= 20; attempt++) {
            int current = attempt;
            try {
                var result = Objects.requireNonNull(transaction.execute(status -> execute(request, requestId, current)));
                strategies.afterCommit(request, result.purchaseId());
                return result;
            } catch (OptimisticRetry conflict) {
                // The whole attempt (including the claim) has rolled back before retry.
                ProductService.delay(ThreadLocalRandom.current().nextInt(1, 6));
            }
        }
        throw new IllegalStateException("The final attempt must persist GAVE_UP.");
    }

    private PurchaseDecision execute(PurchaseRequest request, UUID requestId, int attempt) {
        jdbc.execute("SET LOCAL lock_timeout = '2s'");
        jdbc.execute("SET LOCAL statement_timeout = '5s'");
        admission.lockRequest(request.clientId(), request.keyHash());
        int claimed = jdbc.update("""
                INSERT INTO purchase_requests
                    (client_id,key_hash,fingerprint,product_id,quantity,strategy,original_request_id)
                VALUES (?,?,?,?,?,?,?)
                ON CONFLICT (client_id,key_hash) DO NOTHING
                """, request.clientId(), request.keyHash(), request.fingerprint(),
                request.productId(), request.quantity(), request.strategy(), requestId);

        if (claimed == 0) {
            return replay(request);
        }

        fixtures.check(request);
        var decision = strategies.decide(request, attempt);
        if (decision.outcome() == PurchaseDecision.Outcome.GAVE_UP && attempt < 20) {
            throw new OptimisticRetry();
        }
        var stock = decision.stock();
        var outcome = decision.outcome();
        UUID purchaseId = outcome == PurchaseDecision.Outcome.SOLD ? UUID.randomUUID() : null;
        int completed = jdbc.update("""
                UPDATE purchase_requests
                SET outcome=?,stock_left=?,product_version=?,purchase_id=?,attempts=?,completed_at=clock_timestamp()
                WHERE client_id=? AND key_hash=?
                """, outcome.name(), stock == null ? null : stock.quantity(),
                stock == null ? null : stock.version(), purchaseId, attempt, request.clientId(), request.keyHash());
        if (completed != 1) {
            throw new IllegalStateException("Owned purchase claim disappeared before completion.");
        }
        strategies.beforeCommit(request, purchaseId);
        if (outcome == PurchaseDecision.Outcome.SOLD) {
            invalidation.afterCommit(request.productId());
        }
        return new PurchaseDecision(outcome, request.strategy(), request.productId(), request.quantity(),
                stock == null ? null : stock.quantity(), stock == null ? null : stock.version(),
                attempt, purchaseId, requestId, false);
    }

    private PurchaseDecision replay(PurchaseRequest request) {
        return jdbc.queryForObject("""
                SELECT * FROM purchase_requests WHERE client_id=? AND key_hash=?
                """, (row, index) -> {
            if (!request.fingerprint().equals(row.getString("fingerprint"))) {
                throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT",
                        "This client already used that key for a different purchase.", false);
            }
            return new PurchaseDecision(PurchaseDecision.Outcome.valueOf(row.getString("outcome")),
                    row.getString("strategy"), row.getLong("product_id"), row.getInt("quantity"),
                    row.getObject("stock_left", Integer.class), row.getObject("product_version", Long.class),
                    row.getInt("attempts"), row.getObject("purchase_id", UUID.class),
                    row.getObject("original_request_id", UUID.class), true);
        }, request.clientId(), request.keyHash());
    }

    private static final class OptimisticRetry extends RuntimeException {
    }
}
