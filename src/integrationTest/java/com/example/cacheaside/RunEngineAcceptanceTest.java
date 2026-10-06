package com.example.cacheaside;

import com.example.cacheaside.cache.CacheCoordinator;
import com.example.cacheaside.demo.RunStore;
import com.example.cacheaside.product.ProductService;
import com.example.cacheaside.web.ApiException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import tools.jackson.databind.JsonNode;

import static com.example.cacheaside.PurchaseTestApplication.body;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

class RunEngineAcceptanceTest {
    private static RedisFixture redis;
    private static PurchaseTestApplication app;

    @BeforeAll
    static void start() throws Exception {
        redis = new RedisFixture();
        var arguments = new ArrayList<>(List.of(redis.arguments()));
        arguments.add("--lab.rate-limit.enabled=true");
        arguments.add("--lab.read-delay-ms=300");
        app = new PurchaseTestApplication(Configuration.class, arguments.toArray(String[]::new));
        await().atMost(Duration.ofSeconds(10)).until(() -> app.context.getBean(CacheCoordinator.class).status().readiness()
                == CacheCoordinator.Readiness.READY);
    }

    @AfterAll
    static void stop() throws Exception {
        try { if (app != null) { app.close(); } }
        finally { if (redis != null) { redis.close(); } }
    }

    @Test
    void fiftyRealHttpBuyersUseDistinctIdentitiesAndPersistTenUnitSales() throws Exception {
        for (String strategy : List.of("ATOMIC_SQL", "PESSIMISTIC")) {
            UUID id = start("{\"strategy\":\"" + strategy + "\"}");
            JsonNode result = complete(id);
            assertThat(result.get("state").asString()).isEqualTo("COMPLETED");
            assertThat(result.get("httpAttempts").asInt()).isEqualTo(50);
            assertThat(result.get("httpResponses").asInt()).isEqualTo(50);
            long product = result.get("fixtures").get(0).get("productId").asLong();
            assertThat(app.stock(product)).isZero();
            assertThat(app.sold(product)).isEqualTo(10);
            var attempts = app.context.getBean(RunStore.class).attempts(id);
            assertThat(attempts.stream().map(a -> a.get("clientId")).distinct()).hasSize(50);
            assertThat(attempts).allSatisfy(attempt -> {
                JsonNode response = (JsonNode) attempt.get("response");
                assertThat(response.get("requestId").asString()).isNotBlank();
                assertThat(response.get("runId").asString()).isEqualTo(id.toString());
                assertThat(response.get("rateLimit").asString()).isEqualTo("ALLOWED");
                assertThat((Integer) attempt.get("httpStatus")).isIn(200, 409);
            });
            assertThat(body(app.request("GET", "/demo/runs/" + id + "/export", null, "observer", "x"))
                    .get("attempts").size()).isEqualTo(50);
        }
    }

    @Test
    void quantityAccountingUsesIsolatedFixturesAndRepeatedRunsNeverReuseKeys() throws Exception {
        UUID first = start("{\"buyers\":5,\"concurrency\":5,\"stock\":10,\"quantity\":3}");
        JsonNode one = complete(first);
        UUID second = start("{\"buyers\":5,\"concurrency\":5,\"stock\":10,\"quantity\":3}");
        JsonNode two = complete(second);
        long a = one.get("fixtures").get(0).get("productId").asLong();
        long b = two.get("fixtures").get(0).get("productId").asLong();
        assertThat(a).isNotEqualTo(b);
        assertThat(app.sold(a)).isEqualTo(9);
        assertThat(app.stock(a)).isEqualTo(1);
        assertThat(app.sold(b)).isEqualTo(9);
        assertThat(app.jdbc.queryForObject("SELECT count(DISTINCT client_id) FROM demo_run_attempts WHERE run_id IN (?,?)",
                Integer.class, first, second)).isEqualTo(10);
    }

    @Test
    void activeRunRejectsOtherRunsFixtureMutationsAndGlobalCacheClear() throws Exception {
        UUID run = start("{\"scenario\":\"READ\",\"buyers\":100,\"concurrency\":1}");
        await().atMost(Duration.ofSeconds(5)).until(() -> !app.context.getBean(RunStore.class).fixtures(run).isEmpty());
        long product = app.context.getBean(RunStore.class).fixtures(run).get(0).productId();
        assertThat(app.request("POST", "/demo/runs", "{}", "observer", "x").statusCode()).isEqualTo(409);
        assertThat(app.request("PATCH", "/products/" + product, "{\"stock\":999}", "observer", "x").statusCode()).isEqualTo(409);
        assertThat(app.request("GET", "/products/" + product, null, "observer", "x").statusCode()).isEqualTo(409);
        assertThat(app.request("DELETE", "/cache/products", null, "observer", "x").statusCode()).isEqualTo(409);
        assertThatThrownBy(() -> app.context.getBean(ProductService.class).update(product,
                PurchaseTestApplication.JSON.readTree("{\"stock\":999}"))).isInstanceOf(ApiException.class);
        assertThat(app.request("POST", "/demo/runs/" + run + "/cancel", null, "observer", "x").statusCode()).isEqualTo(200);
        complete(run);
        assertThat(app.stock(product)).isEqualTo(10);
        assertThat(app.request("GET", "/products/" + product, null, "observer", "x").statusCode()).isEqualTo(200);
    }

    @Test
    void invalidRunParametersNeverCreatePersistentOrActiveWork() throws Exception {
        for (String invalid : List.of("{\"buyers\":101}", "{\"concurrency\":51}", "{\"host\":\"example.com\"}")) {
            assertThat(app.request("POST", "/demo/runs", invalid, "observer", "x").statusCode()).isEqualTo(400);
        }
        assertThat(app.jdbc.queryForObject("SELECT count(*) FROM demo_runs WHERE active", Integer.class)).isZero();
    }

    private static UUID start(String parameters) throws Exception {
        var response = app.request("POST", "/demo/runs", parameters, "observer", "x");
        assertThat(response.statusCode()).as(response.body()).isEqualTo(202);
        return UUID.fromString(body(response).get("runId").asString());
    }

    private static JsonNode complete(UUID run) throws Exception {
        await().atMost(Duration.ofSeconds(45)).until(() ->
                !(boolean) app.context.getBean(RunStore.class).snapshot(run).get("active"));
        return body(app.request("GET", "/demo/runs/" + run, null, "observer", "x"));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Configuration { }
}
