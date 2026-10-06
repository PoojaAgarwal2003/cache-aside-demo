package com.example.cacheaside.demo;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class RunAccountingTest {
    private final JsonMapper json = new JsonMapper();

    @Test
    void nearestRankSeparatesSuccessSamplesFromRejectionsAndUnknowns() {
        var attempts = List.of(
                attempt(1.0, 200, "SOLD", "RESPONSE"),
                attempt(2.0, 409, "OUT_OF_STOCK", "RESPONSE"),
                attempt(3.0, 200, "SOLD", "RESPONSE"),
                attempt(4.0, 503, "HTTP_UNKNOWN", "UNKNOWN"));
        var result = json.valueToTree(RunAccounting.httpMetrics(attempts, 1000));
        assertThat(result.get("allAttemptLatency").get("sampleCount").asInt()).isEqualTo(4);
        assertThat(result.get("allAttemptLatency").get("p50").asDouble()).isEqualTo(2);
        assertThat(result.get("allAttemptLatency").get("p95").asDouble()).isEqualTo(4);
        assertThat(result.get("successLatency").get("sampleCount").asInt()).isEqualTo(2);
        assertThat(result.get("successLatency").get("p50").asDouble()).isEqualTo(1);
        assertThat(result.get("successLatency").get("p99").asDouble()).isEqualTo(3);
        assertThat(result.get("businessRejections").asInt()).isEqualTo(1);
        assertThat(result.get("errorsOrUnknown").asInt()).isEqualTo(1);
        assertThat(result.get("httpAttemptsPerSecond").asDouble()).isEqualTo(4);
        assertThat(result.get("measurementWindowMs").asDouble()).isEqualTo(1000);
        assertThat(json.valueToTree(RunAccounting.httpMetrics(attempts, 0))
                .get("httpAttemptsPerSecond").isNull()).isTrue();
        assertThat(json.valueToTree(RunAccounting.httpMetrics(List.of(), 0))
                .get("successLatency").get("p50").isNull()).isTrue();
    }

    private Map<String, Object> attempt(double latency, int status, String code, String state) {
        return Map.of("phase", "MEASURED", "elapsedMs", latency, "httpStatus", status, "state", state,
                "response", json.readTree("{\"body\":{\"code\":\"" + code + "\"}}"));
    }

    @Test
    void retriesDoNotCountAsAdditionalDispatchedBuyersAndCasesStayDistinct() {
        var first = new java.util.HashMap<>(attempt(1, 200, "SOLD", "RESPONSE"));
        first.put("caseIndex", 0); first.put("buyer", 1);
        var replay = new java.util.HashMap<>(first); replay.put("phase", "RETRY");
        var secondCase = new java.util.HashMap<>(first); secondCase.put("caseIndex", 1);
        var result = RunAccounting.httpMetrics(List.of(first, replay, secondCase), 1000);
        assertThat(result.get("attempts")).isEqualTo(3);
        assertThat(result.get("buyersDispatched")).isEqualTo(2L);
    }
}
