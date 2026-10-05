package com.example.cacheaside.purchase;

import com.example.cacheaside.web.ApiException;
import com.example.cacheaside.cache.RedisAccess;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Component
public class RedisStockClient {
    public static final long COUNTER_TTL_MS = 60_000;
    public static final long RESERVATION_TTL_MS = 120_000;
    private static final Logger LOG = LoggerFactory.getLogger(RedisStockClient.class);
    private static final DefaultRedisScript<String> RESERVE = script("reserve-stock");
    private static final DefaultRedisScript<String> RELEASE = script("release-stock");
    private static final DefaultRedisScript<String> RESET = script("reset-stock");
    private static final DefaultRedisScript<String> INSPECT = script("inspect-stock");
    private final RedisAccess access;
    private final String namespace;

    public RedisStockClient(RedisAccess access, @Value("${spring.flyway.default-schema}") String namespace) {
        this.access = access;
        this.namespace = namespace;
    }

    public String counterKey(long productId) {
        return "flashsale:{" + namespace + ":" + productId + "}:stock";
    }

    public String reservationKey(long productId, UUID reservationId) {
        return "flashsale:{" + namespace + ":" + productId + "}:reservation:" + reservationId;
    }

    String reserve(StockAdmissionService.Reservation reservation) {
        return call(RESERVE, List.of(counterKey(reservation.productId()),
                        reservationKey(reservation.productId(), reservation.id())),
                reservation.epoch().toString(), Integer.toString(reservation.quantity()),
                Long.toString(RESERVATION_TTL_MS));
    }

    String release(StockAdmissionService.Reservation reservation) {
        return call(RELEASE, List.of(counterKey(reservation.productId()),
                        reservationKey(reservation.productId(), reservation.id())),
                reservation.epoch().toString(), Integer.toString(reservation.quantity()));
    }

    void reset(long productId, UUID epoch, int stock) {
        String result = call(RESET, List.of(counterKey(productId)), epoch.toString(),
                Integer.toString(stock), Long.toString(COUNTER_TTL_MS));
        if (!"RESET".equals(result)) {
            throw ApiException.unavailable("ADMISSION_UNTRUSTED", "Unexpected Redis reset result.");
        }
    }

    Snapshot inspect(long productId) {
        try {
            String value = call(INSPECT, List.of(counterKey(productId)));
            if ("ABSENT".equals(value)) {
                return new Snapshot("AVAILABLE", "ABSENT", null, null, null, null);
            }
            if ("CORRUPT".equals(value)) {
                return new Snapshot("AVAILABLE", "CORRUPT", null, null, null, "Invalid counter fields.");
            }
            try {
                String[] parts = value.split(":");
                if (parts.length != 3) {
                    throw new IllegalArgumentException("Invalid counter shape");
                }
                UUID epoch = UUID.fromString(parts[0]);
                int stock = Integer.parseInt(parts[1]);
                long ttl = Long.parseLong(parts[2]);
                if (stock < 0 || ttl <= 0) {
                    throw new IllegalArgumentException("Invalid counter values");
                }
                return new Snapshot("AVAILABLE", "PRESENT", epoch, stock, ttl, null);
            } catch (IllegalArgumentException invalid) {
                LOG.warn("Corrupt stock admission counter (productId={})", productId);
                return new Snapshot("AVAILABLE", "CORRUPT", null, null, null, "Invalid counter value.");
            }
        } catch (ApiException unavailable) {
            return new Snapshot("UNAVAILABLE", "UNKNOWN", null, null, null, unavailable.code());
        }
    }

    private String call(DefaultRedisScript<String> script, List<String> keys, String... arguments) {
        try {
            String result = access.execute(RedisAccess.Domain.STOCK_ADMISSION, script, keys, arguments);
            if (result == null) {
                throw ApiException.unavailable("REDIS_UNAVAILABLE", "Redis returned no admission decision.");
            }
            return result;
        } catch (RedisAccess.Unavailable failure) {
            LOG.warn("Stock admission Redis call failed (type={})", failure.getClass().getSimpleName());
            throw ApiException.unavailable("REDIS_UNAVAILABLE",
                    "Redis admission is unavailable; no alternate purchase strategy was used.");
        }
    }

    private static DefaultRedisScript<String> script(String name) {
        return RedisAccess.script(name);
    }

    public record Snapshot(String availability, String presence, UUID epoch, Integer stock,
                           Long remainingTtlMs, String error) { }
}
