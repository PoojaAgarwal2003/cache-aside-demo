package com.example.cacheaside;

import com.example.cacheaside.purchase.PurchaseProbe;
import com.example.cacheaside.purchase.PurchaseRequest;
import com.example.cacheaside.purchase.PurchaseStrategy;
import java.net.http.HttpResponse;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import static com.example.cacheaside.PurchaseTestApplication.body;
import static com.example.cacheaside.PurchaseTestApplication.simultaneous;
import static org.assertj.core.api.Assertions.assertThat;

class PurchaseStrategiesAcceptanceTest {
    private static PurchaseTestApplication app;
    private static ControlledProbe probe;

    @BeforeAll
    static void start() {
        app = new PurchaseTestApplication(ProbeConfiguration.class);
        probe = app.context.getBean(ControlledProbe.class);
    }

    @AfterEach
    void resetProbe() {
        probe.hook = (request, stock, version, attempt) -> { };
    }

    @AfterAll
    static void stop() throws Exception {
        if (app != null) {
            app.close();
        }
    }

    @Test
    void healthyPessimisticFiftyBuyersSellExactlyTenUnits() throws Exception {
        long id = app.fixture(10);
        var responses = buyers(id, "PESSIMISTIC", 50, 1, false);
        assertThat(responses).filteredOn(response -> response.statusCode() == 200).hasSize(10);
        assertThat(responses).filteredOn(response -> response.statusCode() == 409).hasSize(40);
        assertThat(responses).allSatisfy(response -> assertThat(body(response).get("code").asString())
                .isIn("SOLD", "OUT_OF_STOCK"));
        assertThat(app.sold(id)).isEqualTo(10);
        assertThat(app.stock(id)).isZero();
        assertUniqueSales(id, 10);
    }

    @ParameterizedTest
    @EnumSource(value = PurchaseStrategy.class, names = {"ATOMIC_SQL", "PESSIMISTIC", "OPTIMISTIC"})
    void quantityConcurrencyConservesStockAndRejectionsRemainStable(PurchaseStrategy strategy) throws Exception {
        long id = app.fixture(11);
        var responses = buyers(id, strategy.name(), 25, 2, false);
        assertThat(responses).allSatisfy(response -> {
            assertThat(response.statusCode()).isIn(200, 409);
            assertThat(body(response).get("code").asString()).isIn("SOLD", "OUT_OF_STOCK", "GAVE_UP");
        });
        assertThat(app.stock(id)).isGreaterThanOrEqualTo(0);
        assertThat(app.sold(id) + app.stock(id)).isEqualTo(11);
        assertUniqueSales(id, app.sold(id) / 2);
        var rejection = app.purchase(id, strategy.name(), "insufficient-" + id, "key", 1000);
        assertThat(body(rejection).get("code").asString()).isEqualTo("OUT_OF_STOCK");
        app.jdbc.update("UPDATE products SET stock=1000 WHERE id=?", id);
        var replay = app.purchase(id, strategy.name(), "insufficient-" + id, "key", 1000);
        assertThat(body(replay).get("code").asString()).isEqualTo("OUT_OF_STOCK");
        assertThat(body(replay).get("replayed").asBoolean()).isTrue();
        assertThat(app.stock(id)).isEqualTo(1000);
    }

    @ParameterizedTest
    @EnumSource(value = PurchaseStrategy.class, names = {"PESSIMISTIC", "OPTIMISTIC"})
    void concurrentSameKeyOnlyCommitsOnceAndConflictingPayloadFails(PurchaseStrategy strategy) throws Exception {
        long id = app.fixture(10);
        var responses = buyers(id, strategy.name(), 20, 2, true);
        assertThat(responses).allSatisfy(response -> assertThat(response.statusCode()).isEqualTo(200));
        assertThat(responses.stream().map(PurchaseTestApplication::body)
                .filter(node -> !node.get("replayed").asBoolean()).count()).isEqualTo(1);
        assertThat(responses.stream().map(response -> body(response).get("purchaseId").asString())
                .distinct().count()).isEqualTo(1);
        assertThat(app.sold(id)).isEqualTo(2);
        assertThat(app.stock(id)).isEqualTo(8);
        var conflict = app.purchase(id, strategy.name(), "buyer-" + id + "-0", "key", 1);
        assertThat(body(conflict).get("code").asString()).isEqualTo("IDEMPOTENCY_CONFLICT");
    }

    @Test
    void externalSqlConflictRetriesWithFreshVersionAndTransaction() throws Exception {
        long id = app.fixture(3);
        var transactions = new HashSet<Long>();
        var versions = new ArrayList<Long>();
        probe.hook = (request, stock, version, attempt) -> {
            transactions.add(app.jdbc.queryForObject("SELECT txid_current()", Long.class));
            versions.add(version);
            if (attempt == 1) {
                externalVersionChange(id);
            }
        };
        var response = app.purchase(id, "OPTIMISTIC", "external-" + id, "key", 2);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(body(response).get("attempts").asInt()).isEqualTo(2);
        assertThat(body(response).get("version").asLong()).isEqualTo(2);
        assertThat(transactions).hasSize(2);
        assertThat(versions).containsExactly(0L, 1L);
        assertThat(app.stock(id) + app.sold(id)).isEqualTo(3);
    }

    @Test
    void twentyConflictsPersistGaveUpWithoutPretendingStockIsGone() throws Exception {
        long id = app.fixture(3);
        Set<Long> transactions = new HashSet<>();
        AtomicInteger reads = new AtomicInteger();
        probe.hook = (request, stock, version, attempt) -> {
            reads.incrementAndGet();
            transactions.add(app.jdbc.queryForObject("SELECT txid_current()", Long.class));
            externalVersionChange(id);
        };
        var response = app.purchase(id, "OPTIMISTIC", "exhausted-" + id, "key", 2);
        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(body(response).get("code").asString()).isEqualTo("GAVE_UP");
        assertThat(body(response).get("attempts").asInt()).isEqualTo(20);
        assertThat(transactions).hasSize(20);
        assertThat(app.stock(id)).isEqualTo(3);
        assertThat(app.sold(id)).isZero();
        assertThat(body(app.purchase(id, "OPTIMISTIC", "exhausted-" + id, "key", 2))
                .get("replayed").asBoolean()).isTrue();
        assertThat(reads.get()).isEqualTo(20);
        assertThat(app.jdbc.queryForObject("SELECT count(*) FROM purchase_requests WHERE product_id=?",
                Integer.class, id)).isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(value = PurchaseStrategy.class, names = {"ATOMIC_SQL", "PESSIMISTIC", "OPTIMISTIC"})
    void rejectsNegativeFixtureAndDistinguishesAbsentProduct(PurchaseStrategy strategy) throws Exception {
        long id = app.fixture(-1);
        var invalid = app.purchase(id, strategy.name(), "negative-" + id, "key", 1);
        assertThat(invalid.statusCode()).isEqualTo(409);
        assertThat(body(invalid).get("code").asString()).isEqualTo("INVALID_FIXTURE");
        assertThat(app.sold(id)).isZero();
        assertThat(app.purchase(Long.MAX_VALUE, strategy.name(), "absent-" + id, "key", 1).statusCode())
                .isEqualTo(404);
    }

    private List<HttpResponse<String>> buyers(long id, String strategy, int buyers, int quantity, boolean sameKey)
            throws Exception {
        var tasks = new ArrayList<Callable<HttpResponse<String>>>();
        for (int buyer = 0; buyer < buyers; buyer++) {
            String client = "buyer-" + id + "-" + (sameKey ? 0 : buyer);
            tasks.add(() -> app.purchase(id, strategy, client, "key", quantity));
        }
        return simultaneous(tasks);
    }

    private void assertUniqueSales(long id, int count) {
        assertThat(app.jdbc.queryForObject(
                "SELECT count(DISTINCT purchase_id) FROM purchase_ledger WHERE product_id=?", Integer.class, id))
                .isEqualTo(count);
        assertThat(app.jdbc.queryForObject(
                "SELECT count(*) FROM purchase_requests WHERE product_id=? AND outcome='IN_PROGRESS'", Integer.class, id))
                .isZero();
    }

    private static void externalVersionChange(long id) {
        try (var connection = DriverManager.getConnection(app.database.scopedUrl(),
                app.database.username, app.database.password);
             var update = connection.prepareStatement("UPDATE products SET name='External edit' WHERE id=?")) {
            update.setLong(1, id);
            assertThat(update.executeUpdate()).isEqualTo(1);
        } catch (java.sql.SQLException failure) {
            throw new IllegalStateException("Controlled external SQL failed", failure);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ProbeConfiguration {
        @Bean
        ControlledProbe purchaseProbe() {
            return new ControlledProbe();
        }
    }

    static class ControlledProbe implements PurchaseProbe {
        volatile ReadHook hook = (request, stock, version, attempt) -> { };

        @Override
        public void afterRead(PurchaseRequest request, int stock, long version, int attempt) {
            hook.accept(request, stock, version, attempt);
        }
    }

    @FunctionalInterface
    interface ReadHook {
        void accept(PurchaseRequest request, int stock, long version, int attempt);
    }
}
