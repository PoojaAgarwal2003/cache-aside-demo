package com.example.cacheaside.purchase;

import com.example.cacheaside.web.ApiException;
import com.example.cacheaside.web.LabProperties;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class PurchaseFixturePolicy {
    private final JdbcTemplate jdbc;
    private final LabProperties properties;
    private final TransactionTemplate registration;

    public PurchaseFixturePolicy(JdbcTemplate jdbc, LabProperties properties, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.properties = properties;
        registration = new TransactionTemplate(manager);
        registration.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        registration.setTimeout(5);
    }

    void prepare(PurchaseRequest request) {
        if ("NONE".equals(request.strategy()) && !properties.demoEnabled()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "DEMO_DISABLED",
                    "NONE is deliberately unsafe and requires the explicit demo flag.", false);
        }
        // Commit classification before inventory work, so the first NONE buyers
        // do not accidentally serialize on an uncommitted classification row.
        registration.executeWithoutResult(status -> {
            jdbc.execute("SET LOCAL lock_timeout = '2s'");
            jdbc.update("""
                    INSERT INTO purchase_fixture_modes(product_id,mode)
                    SELECT id,? FROM products WHERE id=?
                    ON CONFLICT (product_id) DO NOTHING
                    """, mode(request), request.productId());
        });
    }

    void check(PurchaseRequest request) {
        var modes = jdbc.queryForList("SELECT mode FROM purchase_fixture_modes WHERE product_id=?",
                String.class, request.productId());
        if (!modes.isEmpty() && !modes.get(0).equals(mode(request))) {
            throw new ApiException(HttpStatus.CONFLICT, "FIXTURE_MODE_CONFLICT",
                    "NONE and protected purchases must use different products. Create a fresh fixture.", false);
        }
    }

    private String mode(PurchaseRequest request) {
        return "NONE".equals(request.strategy()) ? "UNSAFE" : "PROTECTED";
    }
}
