package com.example.cacheaside.cache;

import java.util.List;
import java.util.function.Function;
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

    public RedisAccess(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public String execute(Domain domain, DefaultRedisScript<String> script, List<String> keys, String... args) {
        return call(domain, template -> template.execute(script, keys, (Object[]) args));
    }

    public void ping(Domain domain) {
        String result = call(domain, template -> {
            try (var connection = template.getConnectionFactory().getConnection()) {
                return connection.ping();
            }
        });
        if (!"PONG".equals(result)) {
            throw new Unavailable(domain, "Unexpected health response.");
        }
    }

    private <T> T call(Domain domain, Function<StringRedisTemplate, T> operation) {
        try {
            T value = operation.apply(redis);
            if (value == null) {
                throw new Unavailable(domain, "Redis returned no result.");
            }
            return value;
        } catch (DataAccessException failure) {
            LOG.warn("Redis operation failed (domain={}, type={})", domain.label, failure.getClass().getSimpleName());
            throw new Unavailable(domain, "Redis dependency unavailable.");
        }
    }

    public static DefaultRedisScript<String> script(String name) {
        var script = new DefaultRedisScript<String>();
        script.setLocation(new ClassPathResource("redis/" + name + ".lua"));
        script.setResultType(String.class);
        return script;
    }

    public static final class Unavailable extends RuntimeException {
        public Unavailable(Domain domain, String message) {
            super(domain.label + ": " + message);
        }
    }
}
