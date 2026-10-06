package com.example.cacheaside;

import com.example.cacheaside.cache.CacheCoordinator;
import com.example.cacheaside.demo.RunStore;
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
import static org.awaitility.Awaitility.await;

class RunScenariosAcceptanceTest {
    private static RedisFixture redis;
    private static PurchaseTestApplication app;

    @BeforeAll
    static void start() throws Exception {
        redis = new RedisFixture();
        var arguments = new ArrayList<>(List.of(redis.arguments()));
        arguments.addAll(List.of("--lab.rate-limit.enabled=true", "--lab.read-delay-ms=200", "--lab.purchase-delay-ms=10"));
        app = new PurchaseTestApplication(Configuration.class, arguments.toArray(String[]::new));
        ready();
    }
    @AfterAll
    static void stop() throws Exception {
        try { if (app != null) { app.close(); } }
        finally { if (redis != null) { redis.close(); } }
    }

    @Test
    void allFiveComparisonKeepsProtectedSafetyAndIndependentFixtures() throws Exception {
        var exported = finish(start("{\"scenario\":\"COMPARE\"}"));
        var run = exported.get("run");
        assertThat(run.get("state").asString()).isEqualTo("COMPLETED");
        assertThat(run.get("fixtures").size()).isEqualTo(5);
        assertThat(run.get("result").get("intendedBuyerOperations").asInt()).isEqualTo(250);
        for (JsonNode entry : run.get("result").get("cases")) {
            assertThat(entry.get("conservation").asBoolean()).isTrue();
            if (!"NONE".equals(entry.get("strategy").asString())) {
                assertThat(entry.get("inventoryVerdict").asString()).isEqualTo("PASS");
                assertThat(entry.get("soldQuantity").asInt()).isEqualTo(10);
                assertThat(entry.get("finalStock").asInt()).isZero();
                assertThat(entry.get("http").get("errorsOrUnknown").asInt()).isZero();
            } else { assertThat(entry.get("unsafeStrategy").asBoolean()).isTrue(); }
        }
        assertThat(exported.get("attempts").size()).isEqualTo(251);
    }

    @Test
    void coldWarmAndStampedeComparisonMeasureActualQueriesAndSources() throws Exception {
        var cold = finish(start("{\"scenario\":\"COLD_WARM\"}")).get("run").get("result");
        assertThat(cold.get("observations").get("coldThenWarmObserved").asBoolean()).isTrue();
        assertThat(cold.get("databaseWork").get("USER_READ").get("reads").asInt()).isEqualTo(1);
        assertThat(cold.get("http").get("terminalReadSources").get("DATABASE").asInt()).isEqualTo(1);
        assertThat(cold.get("http").get("terminalReadSources").get("REDIS_CACHE").asInt()).isEqualTo(1);
        var burst = finish(start("{\"scenario\":\"STAMPEDE\",\"buyers\":12,\"concurrency\":6}")).get("run").get("result");
        assertThat(burst.get("caseDatabaseWork").get("0").get("USER_READ").get("reads").asInt()).isEqualTo(1);
        assertThat(burst.get("caseDatabaseWork").get("1").get("USER_READ").get("reads").asInt()).isGreaterThan(1);
        assertThat(burst.get("cases").size()).isEqualTo(2);
    }

    @Test
    void staleReaderHookRejectsActualOldPublicationAfterHttpWriterCommits() throws Exception {
        var exported = finish(start("{\"scenario\":\"STALE_FILL\"}"));
        var result = exported.get("run").get("result");
        assertThat(exported.get("run").get("state").asString()).isEqualTo("COMPLETED");
        assertThat(result.get("observations").get("stalePublicationRejected").asBoolean()).isTrue();
        assertThat(result.get("observations").get("freshRead").get("data").get("name").asString())
                .isEqualTo("Committed newer run value");
        assertThat(result.get("cases").get(0).get("finalVersion").asInt()).isEqualTo(1);
    }

    @Test
    void discardedResponseRetriesSameKeyAndReconcilesOnlyOneQuantityDecrement() throws Exception {
        var exported = finish(start("{\"scenario\":\"LOST_RESPONSE\",\"quantity\":2,\"stock\":5}"));
        var result = exported.get("run").get("result");
        assertThat(exported.get("attempts").size()).isEqualTo(2);
        assertThat(exported.get("attempts").get(0).get("state").asString()).isEqualTo("DISCARDED");
        assertThat(exported.get("attempts").get(0).get("keyHash")).isEqualTo(exported.get("attempts").get(1).get("keyHash"));
        assertThat(result.get("observations").get("sameKeyReplayObserved").asBoolean()).isTrue();
        assertThat(result.get("soldQuantity").asInt()).isEqualTo(2);
        assertThat(result.get("uniqueSales").asInt()).isEqualTo(1);
        assertThat(result.get("cases").get(0).get("finalStock").asInt()).isEqualTo(3);
        assertThat(result.get("keysReconciled").asInt()).isEqualTo(1);
        assertThat(result.get("unknownHttpOutcomes").asInt()).isEqualTo(1);
        assertThat(result.get("invariantVerdict").asString()).isEqualTo("PASS");
    }

    @Test
    void realRedisStopAndRestartShowFallbackAdmissionFailureAndObservedRecovery() throws Exception {
        redis.stopServer(true);
        UUID run;
        try {
            run = start("{\"scenario\":\"OUTAGE\",\"durationSeconds\":60}");
            await().atMost(Duration.ofSeconds(15)).until(() -> app.jdbc.queryForObject(
                    "SELECT count(*) FROM demo_run_events WHERE run_id=? AND kind='WAITING_FOR_REDIS'", Integer.class, run) == 1);
            var attempts = PurchaseTestApplication.JSON.valueToTree(app.context.getBean(RunStore.class).attempts(run));
            assertThat(attempts.get(0).get("response").get("body").get("source").asString()).isEqualTo("DATABASE_FALLBACK");
            assertThat(attempts.get(0).get("response").get("rateLimit").asString()).isEqualTo("BYPASSED");
            assertThat(attempts.get(1).get("response").get("body").get("code").asString()).isEqualTo("SOLD");
            assertThat(attempts.get(3).get("httpStatus").asInt()).isEqualTo(503);
        } finally { redis.restartServer(); }
        var exported = finish(run);
        var result = exported.get("run").get("result");
        assertThat(exported.get("run").get("state").asString()).isEqualTo("COMPLETED");
        assertThat(result.get("observations").get("redisCallUnavailableInitially").asBoolean()).isTrue();
        assertThat(result.get("observations").get("recoveryObserved").asBoolean()).isTrue();
        assertThat(result.get("databaseWork").get("FALLBACK").get("reads").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(result.get("soldQuantity").asInt()).isEqualTo(2);
        assertThat(result.get("invariantVerdict").asString()).isEqualTo("PASS");
        ready();
    }

    private static UUID start(String parameters) throws Exception {
        var response = app.request("POST", "/demo/runs", parameters, "observer", "x");
        assertThat(response.statusCode()).as(response.body()).isEqualTo(202);
        return UUID.fromString(body(response).get("runId").asString());
    }
    private static JsonNode finish(UUID run) throws Exception {
        await().atMost(Duration.ofSeconds(65)).until(() ->
                !(boolean) app.context.getBean(RunStore.class).snapshot(run).get("active"));
        return body(app.request("GET", "/demo/runs/" + run + "/export", null, "observer", "x"));
    }
    private static void ready() {
        await().atMost(Duration.ofSeconds(20)).until(() -> app.context.getBean(CacheCoordinator.class).status().readiness()
                == CacheCoordinator.Readiness.READY);
    }
    @TestConfiguration(proxyBeanMethods = false)
    static class Configuration { }
}
