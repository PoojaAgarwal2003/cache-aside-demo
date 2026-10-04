package com.example.cacheaside.purchase;

import com.example.cacheaside.product.ProductService;
import com.example.cacheaside.web.ApiException;
import com.example.cacheaside.web.LabProperties;
import java.util.Objects;
import java.util.UUID;
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
    private final LabProperties properties;

    public PurchaseService(JdbcTemplate jdbc, PlatformTransactionManager manager, LabProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
        transaction = new TransactionTemplate(manager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(8);
    }

    public PurchaseDecision purchase(PurchaseRequest request, UUID requestId) {
        // execute() returns only after commit. Unknown commit failures escape as
        // retryable errors; a retry resolves the persisted key, never refunds.
        return Objects.requireNonNull(transaction.execute(status -> execute(request, requestId)));
    }

    private PurchaseDecision execute(PurchaseRequest request, UUID requestId) {
        jdbc.execute("SET LOCAL lock_timeout = '2s'");
        jdbc.execute("SET LOCAL statement_timeout = '5s'");
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

        ProductService.delay(properties.purchaseDelayMs());
        var updates = jdbc.query("""
                UPDATE products SET stock=stock-?
                WHERE id=? AND stock>=?
                RETURNING stock, version
                """, (row, index) -> new Stock(row.getInt("stock"), row.getLong("version")),
                request.quantity(), request.productId(), request.quantity());

        PurchaseDecision.Outcome outcome;
        Stock stock = updates.isEmpty() ? null : updates.get(0);
        UUID purchaseId = null;
        if (stock != null) {
            outcome = PurchaseDecision.Outcome.SOLD;
            purchaseId = UUID.randomUUID();
        } else {
            boolean exists = Boolean.TRUE.equals(jdbc.queryForObject(
                    "SELECT EXISTS(SELECT 1 FROM products WHERE id=?)", Boolean.class, request.productId()));
            outcome = exists ? PurchaseDecision.Outcome.OUT_OF_STOCK : PurchaseDecision.Outcome.NOT_FOUND;
        }
        int completed = jdbc.update("""
                UPDATE purchase_requests
                SET outcome=?,stock_left=?,product_version=?,purchase_id=?,completed_at=clock_timestamp()
                WHERE client_id=? AND key_hash=?
                """, outcome.name(), stock == null ? null : stock.quantity(),
                stock == null ? null : stock.version(), purchaseId, request.clientId(), request.keyHash());
        if (completed != 1) {
            throw new IllegalStateException("Owned purchase claim disappeared before completion.");
        }
        return new PurchaseDecision(outcome, request.strategy(), request.productId(), request.quantity(),
                stock == null ? null : stock.quantity(), stock == null ? null : stock.version(),
                1, purchaseId, requestId, false);
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

    private record Stock(int quantity, long version) {
    }
}
