package com.example.cacheaside.demo;

import com.example.cacheaside.web.ApiException;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Single-instance ownership; the private dispatch token is never returned in API data. */
@Component
public class RunGuard {
    private UUID active;
    private String token;
    private long deadline;
    private boolean accepting;
    private int inFlight;
    private final Set<Long> fixtures = new HashSet<>();
    private final ThreadLocal<UUID> caller = new ThreadLocal<>();

    public synchronized String begin(UUID id, int seconds) {
        if (active != null) { throw busy(); }
        active = id;
        token = UUID.randomUUID().toString();
        deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(seconds);
        accepting = true;
        return token;
    }

    public synchronized void own(long productId) { fixtures.add(productId); }

    public synchronized void check(long productId) {
        if (fixtures.contains(productId) && !active.equals(caller.get())) { throw busy(); }
    }

    public synchronized void checkClear() {
        if (active != null && !active.equals(caller.get())) { throw busy(); }
    }

    public synchronized void checkDeadline() {
        if (active != null && active.equals(caller.get()) && System.nanoTime() >= deadline) {
            throw ApiException.unavailable("RUN_DEADLINE", "Run dispatch deadline reached; settle existing work.");
        }
    }

    public synchronized Scope enter(String supplied) {
        if (active == null || !accepting || !token.equals(supplied)) {
            throw new ApiException(HttpStatus.CONFLICT, "RUN_CLOSED", "Run dispatch is not authorized or has closed.", false);
        }
        inFlight++;
        return scope(active, true);
    }

    public Scope local(UUID id) { return scope(id, false); }

    private Scope scope(UUID id, boolean counted) {
        UUID previous = caller.get();
        String previousMdc = MDC.get("runId");
        caller.set(id);
        MDC.put("runId", id.toString());
        return () -> {
            if (previous == null) { caller.remove(); } else { caller.set(previous); }
            if (previousMdc == null) { MDC.remove("runId"); } else { MDC.put("runId", previousMdc); }
            if (counted) { synchronized (this) { inFlight--; notifyAll(); } }
        };
    }

    public synchronized void seal() { accepting = false; }
    public synchronized int inFlight() { return inFlight; }
    public synchronized void finish() {
        if (inFlight != 0) { throw new IllegalStateException("Cannot release a live run fixture."); }
        active = null; token = null; fixtures.clear(); accepting = false;
    }

    public interface Scope extends AutoCloseable { @Override void close(); }

    private ApiException busy() {
        return new ApiException(HttpStatus.CONFLICT, "RUN_BUSY",
                "One experiment owns this fixture; cancel and drain it before changing or reusing it.", true);
    }
}
