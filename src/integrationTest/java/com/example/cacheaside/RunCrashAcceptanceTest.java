package com.example.cacheaside;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.time.Duration;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class RunCrashAcceptanceTest {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    @ParameterizedTest
    @ValueSource(strings = {"BEFORE_COMMIT", "AFTER_COMMIT"})
    void realProcessDeathInterruptsRunAndExportsCommittedKeysWithoutReplay(String phase) throws Exception {
        try (var database = new PostgresFixture(); var redis = new RedisFixture();
             var child = new PurchaseCrashAcceptanceTest.Child(database, redis)) {
            int port = child.start(phase);
            JsonNode started = send(port, "POST", "/demo/runs",
                    "{\"buyers\":1,\"concurrency\":1,\"stock\":5,\"quantity\":2,\"strategy\":\"REDIS_ASSISTED\"}");
            String id = started.get("runId").asString();
            await().atMost(Duration.ofSeconds(15)).until(() -> Files.exists(child.boundary));
            child.kill();
            port = child.start("NONE");
            JsonNode interrupted = send(port, "GET", "/demo/runs/" + id + "/export", null);
            assertThat(interrupted.get("run").get("state").asString()).isEqualTo("INTERRUPTED");
            assertThat(interrupted.get("run").get("active").asBoolean()).isFalse();
            var result = interrupted.get("run").get("result");
            assertThat(result.get("invariantVerdict").asString()).isEqualTo("INCONCLUSIVE");
            assertThat(result.get("soldQuantity").asInt()).isEqualTo("AFTER_COMMIT".equals(phase) ? 2 : 0);
            assertThat(result.get("cases").get(0).get("finalStock").asInt()).isEqualTo("AFTER_COMMIT".equals(phase) ? 3 : 5);
            assertThat(result.get("keysReconciled").asInt()).isEqualTo(1);
            assertThat(result.get("keysWithoutCommittedPurchase").asInt()).isEqualTo("AFTER_COMMIT".equals(phase) ? 0 : 1);
            assertThat(interrupted.get("attempts").size()).isEqualTo(2);
            child.kill();
            port = child.start("NONE");
            assertThat(send(port, "GET", "/demo/runs/" + id + "/export", null)).isEqualTo(interrupted);
            child.kill();
        }
    }

    private JsonNode send(int port, String method, String path, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json").timeout(Duration.ofSeconds(20))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isIn(200, 202);
        return PurchaseTestApplication.JSON.readTree(response.body());
    }
}
