package com.example.cacheaside.demo;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.LongAdder;
import javax.sql.DataSource;
import org.slf4j.MDC;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/** Counts actual JDBC execute calls, including failed calls and rolled-back attempts. */
public class DatabaseWork {
    public enum Purpose { USER_READ, FALLBACK, PURCHASE, LISTENER_ADMIN, VERIFICATION }
    private final ThreadLocal<Purpose> purpose = new ThreadLocal<>();
    private volatile Window window;

    private static final class Counts {
        final LongAdder reads = new LongAdder();
        final LongAdder writes = new LongAdder();
        final LongAdder control = new LongAdder();
        final LongAdder failures = new LongAdder();
        final LongAdder nanos = new LongAdder();
    }
    private static final class Window {
        final String id;
        final Map<Purpose, Counts> counts = new EnumMap<>(Purpose.class);
        Window(UUID id) {
            this.id = id.toString();
            for (var purpose : Purpose.values()) { counts.put(purpose, new Counts()); }
        }
    }

    public void begin(UUID id) { window = new Window(id); }
    public void end() { window = null; }

    public RunGuard.Scope purpose(Purpose value) {
        Purpose previous = purpose.get();
        purpose.set(value);
        return () -> { if (previous == null) { purpose.remove(); } else { purpose.set(previous); } };
    }

    public Map<String, Object> snapshot() {
        Window current = window;
        var result = new LinkedHashMap<String, Object>();
        if (current == null) { return result; }
        current.counts.forEach((name, count) -> result.put(name.name(), Map.of("reads", count.reads.sum(),
                "writes", count.writes.sum(), "control", count.control.sum(), "failed", count.failures.sum(),
                "executeMs", count.nanos.sum() / 1_000_000.0)));
        return result;
    }

    private void record(String sql, long elapsed, boolean failed, boolean listener) {
        Window current = window;
        if (current == null || !listener && !current.id.equals(MDC.get("runId"))) { return; }
        Purpose classification = listener || purpose.get() == null ? Purpose.LISTENER_ADMIN : purpose.get();
        Counts count = current.counts.get(classification);
        String normalized = sql.stripLeading().toUpperCase(java.util.Locale.ROOT);
        if (normalized.startsWith("SELECT") || normalized.startsWith("WITH") || normalized.startsWith("/*")) {
            count.reads.increment();
        } else if (normalized.startsWith("INSERT") || normalized.startsWith("UPDATE") || normalized.startsWith("DELETE")) {
            count.writes.increment();
        } else { count.control.increment(); }
        count.nanos.add(elapsed);
        if (failed) { count.failures.increment(); }
    }

    public void listenerExecute(Statement statement, String sql) throws SQLException {
        long started = System.nanoTime();
        boolean failed = true;
        try { statement.execute(sql); failed = false; }
        finally { record(sql, System.nanoTime() - started, failed, true); }
    }

    private Connection connection(Connection target) {
        return (Connection) Proxy.newProxyInstance(DatabaseWork.class.getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    Object result = invoke(target, method, args);
                    if (result instanceof Statement statement && (method.getName().startsWith("prepare")
                            || method.getName().equals("createStatement"))) {
                        String sql = args != null && args.length > 0 && args[0] instanceof String text ? text : "";
                        return statement(statement, sql);
                    }
                    return result;
                });
    }

    private Statement statement(Statement target, String sql) {
        Class<?> type = target instanceof CallableStatement ? CallableStatement.class
                : target instanceof PreparedStatement ? PreparedStatement.class : Statement.class;
        return (Statement) Proxy.newProxyInstance(DatabaseWork.class.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> {
                    if (!method.getName().startsWith("execute")) { return invoke(target, method, args); }
                    String actual = args != null && args.length > 0 && args[0] instanceof String text ? text : sql;
                    long started = System.nanoTime();
                    boolean failed = true;
                    try {
                        Object result = invoke(target, method, args);
                        failed = false;
                        return result;
                    } finally { record(actual, System.nanoTime() - started, failed, false); }
                });
    }

    private static Object invoke(Object target, java.lang.reflect.Method method, Object[] args) throws Throwable {
        try { return method.invoke(target, args); }
        catch (InvocationTargetException wrapped) { throw wrapped.getCause(); }
    }

    private static final class ObservedSource extends DelegatingDataSource implements AutoCloseable {
        private final DatabaseWork work;
        ObservedSource(DataSource target, DatabaseWork work) { super(target); this.work = work; }
        @Override public Connection getConnection() throws SQLException { return work.connection(super.getConnection()); }
        @Override public Connection getConnection(String user, String password) throws SQLException {
            return work.connection(super.getConnection(user, password));
        }
        @Override public void close() throws Exception {
            if (getTargetDataSource() instanceof AutoCloseable closeable) { closeable.close(); }
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class Instrumentation {
        @Bean static DatabaseWork databaseWork() { return new DatabaseWork(); }
        @Bean static BeanPostProcessor databaseObserver(DatabaseWork work) {
            return new BeanPostProcessor() {
                @Override public Object postProcessAfterInitialization(Object bean, String name) {
                    return bean instanceof DataSource source && !(bean instanceof ObservedSource)
                            ? new ObservedSource(source, work) : bean;
                }
            };
        }
    }
}
