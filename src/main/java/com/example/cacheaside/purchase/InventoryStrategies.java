package com.example.cacheaside.purchase;

import com.example.cacheaside.product.ProductService;
import com.example.cacheaside.web.ApiException;
import com.example.cacheaside.web.LabProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class InventoryStrategies {
    private final JdbcTemplate jdbc;
    private final LabProperties properties;
    private final PurchaseProbe probe;
    private final StockAdmissionService admission;

    public InventoryStrategies(JdbcTemplate jdbc, LabProperties properties,
                               ObjectProvider<PurchaseProbe> probes, Environment environment,
                               StockAdmissionService admission) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.admission = admission;
        probe = environment.acceptsProfiles(Profiles.of("test"))
                ? probes.getIfAvailable(() -> new PurchaseProbe() { }) : new PurchaseProbe() { };
    }

    Decision decide(PurchaseRequest request, int attempt) {
        return switch (PurchaseStrategy.resolve(request.strategy())) {
            case NONE -> unsafe(request, attempt);
            case ATOMIC_SQL -> atomic(request);
            case PESSIMISTIC -> checked(request, attempt, true);
            case OPTIMISTIC -> checked(request, attempt, false);
            case REDIS_ASSISTED -> admitted(request);
        };
    }

    private Decision admitted(PurchaseRequest request) {
        Stock stock = read(request.productId(), false);
        validateFixture(stock);
        if (stock == null) {
            return Decision.rejected(PurchaseDecision.Outcome.NOT_FOUND);
        }
        var reservation = admission.reserve(request);
        if (reservation == null) {
            return Decision.rejected(PurchaseDecision.Outcome.ADMISSION_REJECTED);
        }
        jdbc.execute("SET LOCAL flashsale.redis_admitted='on'");
        probe.afterReservation(request, reservation);
        return atomic(request);
    }

    private Decision unsafe(PurchaseRequest request, int attempt) {
        Stock stock = read(request.productId(), false);
        if (stock == null) {
            return Decision.rejected(PurchaseDecision.Outcome.NOT_FOUND);
        }
        if (stock.quantity() < request.quantity()) {
            return Decision.rejected(PurchaseDecision.Outcome.OUT_OF_STOCK);
        }
        probe.afterRead(request, stock.quantity(), stock.version(), attempt);
        ProductService.delay(properties.purchaseDelayMs());
        var updated = jdbc.query("UPDATE products SET stock=stock-? WHERE id=? RETURNING stock,version",
                (row, index) -> new Stock(row.getInt(1), row.getLong(2)), request.quantity(), request.productId());
        return updated.isEmpty() ? Decision.rejected(PurchaseDecision.Outcome.NOT_FOUND)
                : Decision.sold(updated.get(0));
    }

    private Decision atomic(PurchaseRequest request) {
        ProductService.delay(properties.purchaseDelayMs());
        var updated = jdbc.query("""
                UPDATE products SET stock=stock-? WHERE id=? AND stock>=? RETURNING stock,version
                """, (row, index) -> new Stock(row.getInt(1), row.getLong(2)),
                request.quantity(), request.productId(), request.quantity());
        if (!updated.isEmpty()) {
            return Decision.sold(updated.get(0));
        }
        Stock stock = read(request.productId(), false);
        validateFixture(stock);
        return Decision.rejected(stock == null ? PurchaseDecision.Outcome.NOT_FOUND
                : PurchaseDecision.Outcome.OUT_OF_STOCK);
    }

    private Decision checked(PurchaseRequest request, int attempt, boolean lock) {
        Stock stock = read(request.productId(), lock);
        validateFixture(stock);
        if (stock == null) {
            return Decision.rejected(PurchaseDecision.Outcome.NOT_FOUND);
        }
        if (stock.quantity() < request.quantity()) {
            return Decision.rejected(PurchaseDecision.Outcome.OUT_OF_STOCK);
        }
        probe.afterRead(request, stock.quantity(), stock.version(), attempt);
        ProductService.delay(properties.purchaseDelayMs());
        var updated = jdbc.query("""
                UPDATE products SET stock=stock-? WHERE id=? AND version=? AND stock>=?
                RETURNING stock,version
                """, (row, index) -> new Stock(row.getInt(1), row.getLong(2)),
                request.quantity(), request.productId(), stock.version(), request.quantity());
        if (updated.isEmpty()) {
            return Decision.rejected(PurchaseDecision.Outcome.GAVE_UP);
        }
        return Decision.sold(updated.get(0));
    }

    private Stock read(long id, boolean lock) {
        var rows = jdbc.query("SELECT stock,version FROM products WHERE id=?" + (lock ? " FOR UPDATE" : ""),
                (row, index) -> new Stock(row.getInt(1), row.getLong(2)), id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private void validateFixture(Stock stock) {
        if (stock != null && stock.quantity() < 0) {
            throw new ApiException(HttpStatus.CONFLICT, "INVALID_FIXTURE",
                    "Protected strategies require nonnegative starting stock and isolated fixtures.", false);
        }
    }

    void afterCommit(PurchaseRequest request, java.util.UUID purchaseId) {
        probe.afterCommit(request, purchaseId);
    }

    record Stock(int quantity, long version) { }

    record Decision(PurchaseDecision.Outcome outcome, Stock stock) {
        static Decision sold(Stock stock) {
            return new Decision(PurchaseDecision.Outcome.SOLD, stock);
        }

        static Decision rejected(PurchaseDecision.Outcome outcome) {
            return new Decision(outcome, null);
        }
    }
}
