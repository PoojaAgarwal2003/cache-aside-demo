package com.example.cacheaside.cache;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RedisAccessTest {
    @Test
    void scriptsBelongToApplicationClassLoaderNotFirstRequestContext() {
        var thread = Thread.currentThread();
        var original = thread.getContextClassLoader();
        try {
            thread.setContextClassLoader(new ClassLoader(null) { });
            var script = RedisAccess.script("rate-window");
            assertThat(script.getScriptAsString()).contains("redis.call('TIME')");
            assertThat(script.getSha1()).hasSize(40);
        } finally {
            thread.setContextClassLoader(original);
        }
    }
}
