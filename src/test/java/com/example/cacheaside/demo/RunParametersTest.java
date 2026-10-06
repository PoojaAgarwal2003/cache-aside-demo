package com.example.cacheaside.demo;

import com.example.cacheaside.web.ApiException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RunParametersTest {
    private final JsonMapper json = new JsonMapper();

    @Test
    void defaultsAndCapsAreExplicitAndNeverAcceptATargetHost() {
        var parameters = RunParameters.parse(json.readTree("{}"));
        assertThat(parameters.buyers()).isEqualTo(50);
        assertThat(parameters.stock()).isEqualTo(10);
        assertThat(parameters.quantity()).isEqualTo(1);
        for (String body : new String[]{"[]", "{\"buyers\":101}", "{\"concurrency\":51}",
                "{\"buyers\":1,\"concurrency\":2}", "{\"seed\":1.5}", "{\"quantity\":0}",
                "{\"durationSeconds\":121}", "{\"stock\":-1}", "{\"target\":\"http://example.com\"}",
                "{\"stampedeProtection\":\"false\"}", "{\"buyers\":null}", "{\"strategy\":\"unknown\"}"}) {
            assertThatThrownBy(() -> RunParameters.parse(json.readTree(body))).as(body).isInstanceOf(ApiException.class);
        }
    }
}
