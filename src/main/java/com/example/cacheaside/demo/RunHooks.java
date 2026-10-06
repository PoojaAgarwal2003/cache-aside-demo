package com.example.cacheaside.demo;

import com.example.cacheaside.web.LabProperties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/** Bounded run-fixture coordination and one demo-only stale-reader pause. */
@Component
public class RunHooks {
    public static final class Pause implements AutoCloseable {
        final String run;
        final long product;
        final CountDownLatch loaded = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        Pause(UUID run, long product) { this.run = run.toString(); this.product = product; }
        public boolean awaitLoaded() throws InterruptedException { return loaded.await(5, TimeUnit.SECONDS); }
        @Override public void close() { release.countDown(); }
    }
    private final LabProperties properties;
    private volatile Pause active;
    private final ConcurrentHashMap<Long, CountDownLatch> creations = new ConcurrentHashMap<>();
    public RunHooks(LabProperties properties) { this.properties = properties; }
    public void expectCreation(long product) {
        if (!properties.demoEnabled() || MDC.get("runId") == null || creations.size() >= 5) {
            throw new IllegalStateException("Only bounded demo-run fixtures may await creation notifications.");
        }
        creations.put(product, new CountDownLatch(1));
    }
    public void afterNotification(long product) {
        CountDownLatch creation = creations.get(product);
        if (creation != null) { creation.countDown(); }
    }
    public void awaitCreation(long product) {
        CountDownLatch creation = creations.get(product);
        if (creation == null) { throw new IllegalStateException("Fixture notification was not registered."); }
        try {
            if (!creation.await(3, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Fixture creation notification did not drain before cache measurement.");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Fixture notification wait interrupted.", interrupted);
        } finally { creations.remove(product, creation); }
    }
    public Pause arm(UUID run, long product) {
        if (!properties.demoEnabled() || !run.toString().equals(MDC.get("runId"))) {
            throw new IllegalStateException("A demo run must own the stale-read pause.");
        }
        active = new Pause(run, product);
        return active;
    }
    public void afterLoad(long product) {
        Pause pause = active;
        if (pause == null || product != pause.product || !pause.run.equals(MDC.get("runId"))) { return; }
        pause.loaded.countDown();
        try {
            if (!pause.release.await(4, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Demo stale-reader pause exceeded four seconds.");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Demo stale-reader pause interrupted.", interrupted);
        } finally { if (active == pause) { active = null; } }
    }
    public void clear() {
        creations.clear();
        Pause pause = active;
        if (pause != null) { pause.close(); active = null; }
    }
}
