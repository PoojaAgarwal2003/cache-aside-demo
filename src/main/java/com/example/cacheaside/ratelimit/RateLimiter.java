package com.example.cacheaside.ratelimit;

import com.example.cacheaside.cache.RedisAccess;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.LongAdder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

@Component
public class RateLimiter {
    public enum Outcome { ALLOWED, REJECTED, BYPASSED, DISABLED }
    public record Decision(Outcome outcome, Integer limit, Integer remaining, Integer retryAfterSeconds) { }
    private static final Logger LOG = LoggerFactory.getLogger(RateLimiter.class);
    private static final DefaultRedisScript<String> SCRIPT = RedisAccess.script("rate-window");
    private final RedisAccess access;
    private final RateLimitProperties properties;
    private final JsonMapper json;
    private final String namespace;
    private final LongAdder allowed = new LongAdder();
    private final LongAdder rejected = new LongAdder();
    private final LongAdder bypassed = new LongAdder();

    public RateLimiter(RedisAccess access, RateLimitProperties properties, JsonMapper json,
                       @Value("${spring.flyway.default-schema}") String namespace) {
        if (!namespace.matches("[A-Za-z0-9_]+")) {
            throw new IllegalArgumentException("Rate-limit schema namespace must be safe ASCII.");
        }
        this.access = access;
        this.properties = properties;
        this.json = json;
        this.namespace = namespace;
    }

    public String key(String identity) {
        if (identity == null || !identity.matches("[A-Za-z0-9._-]{1,64}")) {
            throw new IllegalArgumentException("Invalid controlled client identity.");
        }
        return "flashsale:rate:{" + namespace + ":" + identity + "}";
    }

    public Decision decide(String identity) {
        if (!properties.enabled()) {
            return new Decision(Outcome.DISABLED, null, null, null);
        }
        String key = key(identity);
        try {
            var value = json.readTree(access.execute(RedisAccess.Domain.RATE_LIMIT, SCRIPT, List.of(key),
                    Integer.toString(properties.limit()), Integer.toString(properties.windowMs()),
                    UUID.randomUUID().toString()));
            if (value == null || !value.path("outcome").isString()
                    || !value.path("remaining").isIntegralNumber() || !value.path("retryAfterSeconds").isIntegralNumber()) {
                throw new IllegalArgumentException("Malformed rate decision.");
            }
            var outcome = Outcome.valueOf(value.path("outcome").asString());
            int remaining = value.path("remaining").asInt();
            int retry = value.path("retryAfterSeconds").asInt();
            if ((outcome != Outcome.ALLOWED && outcome != Outcome.REJECTED)
                    || remaining < 0 || remaining >= properties.limit()
                    || (outcome == Outcome.ALLOWED && retry != 0)
                    || (outcome == Outcome.REJECTED && (remaining != 0 || retry < 1
                    || retry > (properties.windowMs() + 999) / 1000))) {
                throw new IllegalArgumentException("Invalid rate decision.");
            }
            if (outcome == Outcome.ALLOWED) { allowed.increment(); }
            else { rejected.increment(); }
            return new Decision(outcome, properties.limit(), remaining, retry);
        } catch (RedisAccess.Unavailable | JacksonException | IllegalArgumentException failure) {
            LOG.warn("Lab rate limit explicitly bypassed (type={})", failure.getClass().getSimpleName());
            bypassed.increment();
            return new Decision(Outcome.BYPASSED, null, null, null);
        }
    }

    public Map<String, Object> status() {
        return Map.of("enabled", properties.enabled(), "limit", properties.limit(), "windowMs", properties.windowMs(),
                "allowed", allowed.sum(), "rejected", rejected.sum(), "bypassed", bypassed.sum(),
                "outagePolicy", "FAIL_OPEN_WITHOUT_INVENTED_QUOTA");
    }
}
