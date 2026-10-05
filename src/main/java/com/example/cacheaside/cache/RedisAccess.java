package com.example.cacheaside.cache;

import java.util.List;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.time.Duration;
import java.util.function.Function;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Component
public class RedisAccess {
    public enum Domain {
        PRODUCT_CACHE("product-cache"), RATE_LIMIT("rate-limit"), STOCK_ADMISSION("stock-admission");
        public final String label;
        Domain(String label) { this.label = label; }
    }

    private static final Logger LOG = LoggerFactory.getLogger(RedisAccess.class);
    private final StringRedisTemplate redis;
    private final Map<Domain, CircuitBreaker> breakers = new EnumMap<>(Domain.class);
    public record BreakerStatus(String state, int bufferedCalls, int failedCalls, float failureRate) { }

    public RedisAccess(StringRedisTemplate redis) {
        this.redis = redis;
        var config = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(10).minimumNumberOfCalls(5).failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(10))
                .permittedNumberOfCallsInHalfOpenState(3).build();
        for (Domain domain : Domain.values()) {
            breakers.put(domain, CircuitBreaker.of(domain.label, config));
        }
    }

    public String execute(Domain domain, DefaultRedisScript<String> script, List<String> keys, String... args) {
        return call(domain, false, template -> template.execute(script, keys, (Object[]) args));
    }

    public void ping(Domain domain) {
        String result = call(domain, true, template -> {
            try (var connection = template.getConnectionFactory().getConnection()) {
                String pong = connection.ping();
                if (!"PONG".equals(pong)) {
                    throw new Unavailable(domain, "Unexpected health response.");
                }
                return pong;
            }
        });
        if (!"PONG".equals(result)) {
            throw new Unavailable(domain, "Unexpected health response.");
        }
    }

    public boolean closed(Domain domain) {
        return breakers.get(domain).getState() == CircuitBreaker.State.CLOSED;
    }

    public Map<String, BreakerStatus> status() {
        var result = new LinkedHashMap<String, BreakerStatus>();
        breakers.forEach((domain, breaker) -> {
            var metrics = breaker.getMetrics();
            result.put(domain.label, new BreakerStatus(breaker.getState().name(),
                    metrics.getNumberOfBufferedCalls(), metrics.getNumberOfFailedCalls(), metrics.getFailureRate()));
        });
        return Map.copyOf(result);
    }

    private <T> T call(Domain domain, boolean probe, Function<StringRedisTemplate, T> operation) {
        var breaker = breakers.get(domain);
        if (domain == Domain.PRODUCT_CACHE && !probe && !closed(domain)) {
            throw new Unavailable(domain, "Cache breaker is " + breaker.getState() + "; health probes only.");
        }
        try {
            return breaker.executeSupplier(() -> {
                T value = operation.apply(redis);
                if (value == null) {
                    throw new Unavailable(domain, "Redis returned no result.");
                }
                return value;
            });
        } catch (DataAccessException | CallNotPermittedException failure) {
            LOG.warn("Redis operation failed (domain={}, type={})", domain.label, failure.getClass().getSimpleName());
            throw new Unavailable(domain, "Redis dependency unavailable.");
        }
    }

    public static DefaultRedisScript<String> script(String name) {
        var script = new DefaultRedisScript<String>();
        script.setLocation(new ClassPathResource("redis/" + name + ".lua", RedisAccess.class.getClassLoader()));
        script.setResultType(String.class);
        return script;
    }

    public static final class Unavailable extends RuntimeException {
        public Unavailable(Domain domain, String message) {
            super(domain.label + ": " + message);
        }
    }
}
