package com.example.cacheaside.cache;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;
import java.util.concurrent.atomic.LongAdder;
import org.postgresql.PGConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

@Component
public class DbChangeListener implements SmartLifecycle {
    public record Health(boolean running, boolean connected, Integer backendPid, long notifications) { }
    private static final Logger LOG = LoggerFactory.getLogger(DbChangeListener.class);
    private final CacheCoordinator coordinator;
    private final JsonMapper json;
    private final String url;
    private final String schema;
    private final Properties credentials = new Properties();
    private final CacheProbe probe;
    private final LongAdder notifications = new LongAdder();
    private volatile boolean running;
    private volatile Connection connection;
    private volatile Integer backendPid;
    private Thread thread;

    public DbChangeListener(CacheCoordinator coordinator, JsonMapper json, Environment environment,
                            ObjectProvider<CacheProbe> probes,
                            @Value("${spring.datasource.url}") String url,
                            @Value("${spring.datasource.username}") String user,
                            @Value("${spring.datasource.password}") String password,
                            @Value("${spring.flyway.default-schema}") String schema) {
        this.coordinator = coordinator;
        this.json = json;
        this.url = url + (url.contains("?") ? "&" : "?") + "connectTimeout=3&socketTimeout=3&tcpKeepAlive=true";
        this.schema = schema;
        credentials.setProperty("user", user);
        credentials.setProperty("password", password);
        credentials.setProperty("ApplicationName", "flashsale-product-listener");
        credentials.setProperty("connectTimeout", "3");
        credentials.setProperty("socketTimeout", "3");
        probe = environment.acceptsProfiles(Profiles.of("test"))
                ? probes.getIfAvailable(() -> new CacheProbe() { }) : new CacheProbe() { };
    }

    @Override
    public synchronized void start() {
        if (running) { return; }
        running = true;
        thread = new Thread(this::listen, "flashsale-product-listener");
        thread.setDaemon(true);
        thread.start();
    }

    private void listen() {
        long backoff = 250;
        while (running) {
            try {
                probe.beforeListen();
                if (!running) { break; }
                try (var owned = DriverManager.getConnection(url, credentials)) {
                    connection = owned;
                    if (!running) { break; }
                    try (var statement = owned.createStatement()) {
                        statement.setQueryTimeout(2);
                        statement.execute("LISTEN product_changes");
                    }
                    var postgres = owned.unwrap(PGConnection.class);
                    backendPid = postgres.getBackendPID();
                    coordinator.listenerConnected();
                    backoff = 250;
                    while (running) {
                        var changes = postgres.getNotifications(250);
                        if (changes != null) {
                            for (var change : changes) { changed(change.getParameter()); }
                        }
                        try (var statement = owned.createStatement()) {
                            statement.setQueryTimeout(2);
                            statement.execute("SELECT 1");
                        }
                        coordinator.tick();
                    }
                }
            } catch (SQLException failure) {
                if (running) {
                    LOG.warn("Product listener disconnected (SQLState={})", failure.getSQLState());
                }
            } finally {
                connection = null;
                backendPid = null;
                coordinator.listenerDisconnected("Listener disconnected; notifications cannot be replayed.");
            }
            if (running) {
                try {
                    Thread.sleep(backoff);
                    backoff = Math.min(3_000, backoff * 2);
                } catch (InterruptedException stopped) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    private void changed(String payload) {
        try {
            var change = json.readTree(payload);
            if (!change.has("schema") || !schema.equals(change.get("schema").asString())) {
                return;
            }
            if (!change.has("productId") || !change.get("productId").isIntegralNumber()
                    || !change.get("productId").canConvertToLong() || change.get("productId").asLong() <= 0) {
                throw new IllegalArgumentException("Invalid product notification.");
            }
            coordinator.invalidate(change.get("productId").asLong());
            notifications.increment();
        } catch (JacksonException | IllegalArgumentException invalid) {
            coordinator.bypass("Malformed product notification; rotate epoch before reuse.");
        }
    }

    public Health health() {
        return new Health(running, backendPid != null, backendPid, notifications.sum());
    }

    @Override
    public void stop() {
        running = false;
        coordinator.listenerDisconnected("Listener stopping.");
        var owned = connection;
        if (owned != null) {
            try {
                owned.close();
            } catch (SQLException failure) {
                LOG.warn("Listener shutdown close failed (SQLState={})", failure.getSQLState());
            }
        }
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join(5_000);
                if (thread.isAlive()) { LOG.error("Listener thread exceeded shutdown deadline."); }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                LOG.warn("Listener shutdown interrupted.");
            }
        }
    }

    @Override public boolean isRunning() { return running; }
}
