package com.example.cacheaside.demo;

import com.example.cacheaside.purchase.StockAdmissionService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

@Service
public class RunAccounting {
    private final JdbcTemplate jdbc;
    private final RunStore store;
    private final StockAdmissionService admission;
    private final TransactionTemplate verification;
    private final DatabaseWork database;

    public RunAccounting(JdbcTemplate jdbc, RunStore store, StockAdmissionService admission,
                         PlatformTransactionManager manager, DatabaseWork database) {
        this.jdbc = jdbc; this.store = store; this.admission = admission; this.database = database;
        verification = new TransactionTemplate(manager);
        verification.setTimeout(10);
        verification.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public Map<String, Object> reconcile(UUID run, boolean interrupted, double elapsedMs) {
        try (var scope = database.purpose(DatabaseWork.Purpose.VERIFICATION)) {
            return verification.execute(transaction -> {
                jdbc.execute("SET LOCAL lock_timeout='2s'");
                jdbc.execute("SET LOCAL statement_timeout='3s'");
                var attempts = store.attempts(run);
                var keys = jdbc.query("""
                        SELECT DISTINCT client_id,key_hash FROM demo_run_attempts WHERE run_id=? AND key_hash IS NOT NULL
                        ORDER BY client_id,key_hash
                        """, (row, index) -> Map.of("client", row.getString(1), "hash", row.getString(2)), run);
                for (var key : keys) { admission.lockRequest(key.get("client"), key.get("hash")); }
                // No HTTP dispatch remains possible. The same advisory fences used by
                // purchases also wait out old transactions after a process restart.
                var outcomes = jdbc.query("""
                        SELECT p.* FROM purchase_requests p JOIN
                        (SELECT DISTINCT client_id,key_hash FROM demo_run_attempts WHERE run_id=? AND key_hash IS NOT NULL) a
                        ON p.client_id=a.client_id AND p.key_hash=a.key_hash
                        """, (row, index) -> {
                    var result = new LinkedHashMap<String, Object>();
                    result.put("clientId", row.getString("client_id")); result.put("keyHash", row.getString("key_hash"));
                    result.put("outcome", row.getString("outcome")); result.put("quantity", row.getInt("quantity"));
                    result.put("productId", row.getLong("product_id")); result.put("attempts", row.getInt("attempts"));
                    result.put("purchaseId", row.getString("purchase_id"));
                    result.put("originalRequestId", row.getString("original_request_id"));
                    return result;
                }, run);
                var sales = outcomes.stream().filter(o -> "SOLD".equals(o.get("outcome"))).toList();
                var saleIds = sales.stream().map(o -> o.get("purchaseId")).collect(java.util.stream.Collectors.toSet());
                boolean everyReportedSale = attempts.stream().allMatch(a -> {
                    JsonNode response = (JsonNode) a.get("response");
                    if (response == null || !response.has("body")) { return true; }
                    JsonNode body = response.get("body");
                    return !"SOLD".equals(body.path("code").asString())
                            || saleIds.contains(body.path("purchaseId").asString());
                });
                var cases = new ArrayList<Map<String, Object>>();
                boolean valid = everyReportedSale;
                boolean unresolved = false;
                for (var fixture : store.fixtures(run)) {
                    var stocks = jdbc.queryForList("SELECT stock,version FROM products WHERE id=? FOR UPDATE", fixture.productId());
                    if (stocks.isEmpty()) { throw new IllegalStateException("Run fixture was removed outside the guarded API."); }
                    long stock = ((Number) stocks.get(0).get("stock")).longValue();
                    long version = ((Number) stocks.get(0).get("version")).longValue();
                    long sold = jdbc.queryForObject("SELECT coalesce(sum(quantity),0) FROM purchase_ledger WHERE product_id=?",
                            Long.class, fixture.productId());
                    long unique = jdbc.queryForObject("SELECT count(*) FROM purchase_ledger WHERE product_id=?",
                            Long.class, fixture.productId());
                    boolean conservation = sold + stock == fixture.initialStock();
                    boolean safe = stock >= 0 && sold <= fixture.initialStock();
                    boolean known = unique == sales.stream().filter(o -> ((Long) o.get("productId")) == fixture.productId()).count();
                    long pending = jdbc.queryForObject("""
                            SELECT count(*) FROM stock_reservations WHERE product_id=? AND state='PENDING'
                            """, Long.class, fixture.productId());
                    unresolved |= pending != 0;
                    var result = new LinkedHashMap<String, Object>();
                    result.put("caseIndex", fixture.index()); result.put("label", fixture.label());
                    result.put("productId", fixture.productId()); result.put("strategy", fixture.strategy());
                    result.put("initialStock", fixture.initialStock()); result.put("finalStock", stock);
                    result.put("initialVersion", fixture.initialVersion()); result.put("finalVersion", version);
                    result.put("uniqueSales", unique); result.put("soldQuantity", sold);
                    result.put("conservation", conservation); result.put("nonnegativeStock", stock >= 0);
                    result.put("atMostOnceKeys", sales.size() == saleIds.size());
                    result.put("everyReportedSaleInLedger", everyReportedSale); result.put("allLedgerKeysKnown", known);
                    result.put("pendingAdmissionReservations", pending);
                    result.put("inventoryVerdict", interrupted || pending != 0 ? "INCONCLUSIVE"
                            : conservation && safe && known && everyReportedSale ? "PASS" : "FAIL");
                    result.put("unsafeStrategy", "NONE".equals(fixture.strategy()));
                    result.put("http", httpMetrics(attempts.stream()
                            .filter(a -> (int) a.get("caseIndex") == fixture.index()).toList(), elapsedMs));
                    cases.add(result);
                    valid &= conservation && safe && known;
                }
                var result = new LinkedHashMap<String, Object>();
                result.put("quiescent", true); result.put("elapsedMs", elapsedMs);
                result.put("invariantVerdict", interrupted || unresolved ? "INCONCLUSIVE" : valid ? "PASS" : "FAIL");
                result.put("cases", cases); result.put("purchaseOutcomes", outcomes);
                result.put("unknownHttpOutcomes", attempts.stream().filter(a -> !"RESPONSE".equals(a.get("state"))).count());
                result.put("keysReconciled", keys.size()); result.put("keysWithoutCommittedPurchase", keys.size() - outcomes.size());
                result.put("transactionRetries", outcomes.stream().mapToInt(o -> (int) o.get("attempts") - 1).sum());
                result.put("uniqueSales", sales.size());
                result.put("soldQuantity", sales.stream().mapToInt(o -> (int) o.get("quantity")).sum());
                result.put("http", httpMetrics(attempts, elapsedMs));
                result.put("verificationMeaning", interrupted
                        ? "Interrupted run: committed ledger observed after key fences; never replayed automatically."
                        : "Sealed and drained HTTP; scoped key fences; committed ledger plus final PostgreSQL stock.");
                return result;
            });
        }
    }

    static Map<String, Object> httpMetrics(List<Map<String, Object>> attempts, double elapsedMs) {
        var measured = attempts.stream().filter(a -> "MEASURED".equals(a.get("phase")) || "RETRY".equals(a.get("phase"))).toList();
        var success = measured.stream().filter(a -> a.get("httpStatus") instanceof Number n && n.intValue() >= 200
                && n.intValue() < 300 && "RESPONSE".equals(a.get("state"))).toList();
        long responses = measured.stream().filter(a -> "RESPONSE".equals(a.get("state"))).count();
        long businessRejections = measured.stream().filter(a -> {
            JsonNode response = (JsonNode) a.get("response");
            return response != null && Set.of("OUT_OF_STOCK", "ADMISSION_REJECTED", "GAVE_UP")
                    .contains(response.path("body").path("code").asString());
        }).count();
        var result = new LinkedHashMap<String, Object>();
        result.put("attempts", measured.size()); result.put("responses", responses);
        result.put("successfulResponses", success.size()); result.put("businessRejections", businessRejections);
        result.put("errorsOrUnknown", measured.size() - success.size() - businessRejections);
        result.put("allAttemptLatency", percentiles(measured)); result.put("successLatency", percentiles(success));
        result.put("httpAttemptsPerSecond", elapsedMs > 0 ? measured.size() * 1000.0 / elapsedMs : 0);
        result.put("throughputDenominator", "Whole run duration including setup, drain and verification; not service capacity.");
        return result;
    }

    private static Map<String, Object> percentiles(List<Map<String, Object>> attempts) {
        var samples = attempts.stream().map(a -> a.get("elapsedMs")).filter(Number.class::isInstance)
                .map(Number.class::cast).mapToDouble(Number::doubleValue).sorted().toArray();
        var result = new LinkedHashMap<String, Object>();
        result.put("sampleCount", samples.length); result.put("definition", "Nearest rank: sorted[ceil(p*N)-1]; milliseconds.");
        for (int p : new int[]{50, 95, 99}) {
            result.put("p" + p, samples.length == 0 ? null : samples[(int) Math.ceil(p / 100.0 * samples.length) - 1]);
        }
        return result;
    }
}
