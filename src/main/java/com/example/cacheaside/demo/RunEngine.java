package com.example.cacheaside.demo;

import com.example.cacheaside.product.ProductService;
import com.example.cacheaside.purchase.PurchaseRequest;
import com.example.cacheaside.purchase.PurchaseStrategy;
import com.example.cacheaside.cache.CacheCoordinator;
import com.example.cacheaside.cache.RedisAccess;
import com.example.cacheaside.ratelimit.RateLimiter;
import com.example.cacheaside.web.ApiException;
import com.example.cacheaside.web.LabProperties;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
public class RunEngine {
    private static final Logger LOG = LoggerFactory.getLogger(RunEngine.class);
    private final RunStore store;
    private final RunGuard guard;
    private final LabProperties lab;
    private final JsonMapper json;
    private final RunAccounting accounting;
    private final DatabaseWork database;
    private final CacheCoordinator cache;
    private final RedisAccess redis;
    private final RateLimiter limiter;
    private final RunHooks hooks;
    private final ThreadPoolExecutor coordinator = pool(1, 1, "lab-run-coordinator");
    private final ThreadPoolExecutor workers = pool(50, 50, "lab-run-http");
    private final ThreadPoolExecutor transport = pool(16, 256, "lab-run-transport");
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
            .executor(transport)
            .followRedirects(HttpClient.Redirect.NEVER).proxy(new ProxySelector() {
                @Override public List<Proxy> select(URI uri) {
                    if (!"127.0.0.1".equals(uri.getHost())) { throw new IllegalArgumentException("Local runner only."); }
                    return List.of(Proxy.NO_PROXY);
                }
                @Override public void connectFailed(URI uri, SocketAddress address, IOException error) {
                    LOG.warn("Local runner connection failed (type={})", error.getClass().getSimpleName());
                }
            }).build();
    private volatile int port;
    private volatile Work active;
    private volatile boolean stopping;
    private volatile boolean startupComplete;

    private static final class Work {
        final UUID id;
        final String token;
        final RunParameters parameters;
        final long started = System.nanoTime();
        final long deadline;
        final AtomicBoolean cancel = new AtomicBoolean();
        volatile boolean blocked;
        final Map<String, Object> observations = new LinkedHashMap<>();
        Work(UUID id, String token, RunParameters parameters) {
            this.id = id; this.token = token; this.parameters = parameters;
            deadline = started + TimeUnit.SECONDS.toNanos(parameters.durationSeconds());
        }
    }

    public RunEngine(RunStore store, RunGuard guard, LabProperties lab, JsonMapper json,
                     RunAccounting accounting, DatabaseWork database, CacheCoordinator cache,
                     RedisAccess redis, RateLimiter limiter, RunHooks hooks) {
        this.store = store; this.guard = guard; this.lab = lab; this.json = json;
        this.accounting = accounting; this.database = database;
        this.cache = cache; this.redis = redis; this.limiter = limiter; this.hooks = hooks;
    }

    @EventListener
    public void port(WebServerInitializedEvent event) { port = event.getWebServer().getPort(); }

    @EventListener(ApplicationReadyEvent.class)
    public void interruptOldRuns() {
        for (UUID run : store.unfinished()) {
            guard.begin(run, 120);
            try (var scope = guard.local(run)) {
                store.fixtures(run).forEach(fixture -> guard.own(fixture.productId()));
                guard.seal();
                store.state(run, "INTERRUPTED");
                var result = accounting.reconcile(run, true, 0);
                result.put("elapsedMs", null);
                result.put("databaseWork", "Pre-crash in-memory counters unavailable; not reconstructed from HTTP counts.");
                store.finish(run, "INTERRUPTED", result, "Process restarted before the final result was persisted.");
                guard.finish();
            }
        }
        startupComplete = true;
    }

    public synchronized Map<String, Object> start(RunParameters parameters) {
        requireDemo();
        if (stopping || !startupComplete || port == 0) {
            throw ApiException.unavailable("RUN_UNAVAILABLE", "Runner is not accepting work.");
        }
        UUID id = UUID.randomUUID();
        String token = guard.begin(id, parameters.durationSeconds());
        var work = new Work(id, token, parameters);
        try {
            store.create(id, parameters, Map.of("java", System.getProperty("java.version"),
                    "os", System.getProperty("os.name"), "appVersion", "0.6.0",
                    "readDelayMs", lab.readDelayMs(), "purchaseDelayMs", lab.purchaseDelayMs(),
                    "initialCache", "COLD_NEW_FIXTURE", "seedMeaning", "Jitter only; OS/DB scheduling is not deterministic",
                    "initialReadiness", cache.status(), "rateLimiter", limiter.status()));
            active = work;
            database.begin(id);
            coordinator.execute(() -> execute(work));
            return store.snapshot(id);
        } catch (RuntimeException failure) {
            if (active == null) { guard.finish(); }
            throw failure;
        }
    }

    public synchronized Map<String, Object> cancel(UUID id) {
        requireDemo();
        Work work = active;
        if (work != null && work.id.equals(id)) {
            work.cancel.set(true);
            guard.seal();
            store.state(id, "DRAINING");
        }
        return store.snapshot(id);
    }

    public Map<String, Object> snapshot(UUID id) {
        var result = store.snapshot(id);
        Work work = active;
        result.put("finalizationBlocked", work != null && work.id.equals(id) && work.blocked);
        if ((boolean) result.get("active") && work != null && work.id.equals(id)) {
            double elapsed = (System.nanoTime() - work.started) / 1_000_000.0;
            result.put("liveElapsedMs", elapsed);
            result.put("liveHttp", RunAccounting.httpMetrics(store.attempts(id), elapsed));
            result.put("liveDatabaseWork", database.snapshot());
        }
        return result;
    }

    public synchronized Map<String, Object> reconcile(UUID id) {
        requireDemo();
        Work work = active;
        if (work == null || !work.id.equals(id)) { return store.snapshot(id); }
        if (!work.blocked || workers.getActiveCount() != 0 || guard.inFlight() != 0) {
            throw new ApiException(HttpStatus.CONFLICT, "RUN_NOT_QUIESCENT",
                    "Wait for sealed workers and accepted server requests to finish before retrying reconciliation.", true);
        }
        work.blocked = false;
        coordinator.execute(() -> {
            try (var scope = guard.local(id)) {
                var result = accounting.reconcile(id, false, (System.nanoTime() - work.started) / 1_000_000.0);
                result.put("invariantVerdict", "INCONCLUSIVE");
                result.put("observations", work.observations);
                result.put("databaseWork", database.snapshot());
                store.finish(id, "INCONCLUSIVE", result, "Recovered finalization after a runner failure; no new purchases dispatched.");
                release(work);
            } catch (RuntimeException failure) {
                work.blocked = true;
                LOG.error("Run remains fenced after reconciliation failure (runId={})", id, failure);
            }
        });
        return store.snapshot(id);
    }

    private void execute(Work work) {
        try (var scope = guard.local(work.id)) {
            store.state(work.id, "RUNNING");
            scenario(work);
            guard.seal();
            store.state(work.id, "DRAINING");
            long drainDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (guard.inFlight() > 0 && System.nanoTime() < drainDeadline) { ProductService.delay(20); }
            if (guard.inFlight() != 0) {
                throw new IllegalStateException("Server requests exceeded the drain bound; runner remains fenced.");
            }
            String state = work.cancel.get() ? "CANCELLED" : System.nanoTime() >= work.deadline ? "INCONCLUSIVE" : "COMPLETED";
            var results = accounting.reconcile(work.id, false, (System.nanoTime() - work.started) / 1_000_000.0);
            double elapsed = (System.nanoTime() - work.started) / 1_000_000.0;
            results.put("elapsedMs", elapsed);
            results.put("http", RunAccounting.httpMetrics(store.attempts(work.id), elapsed));
            results.put("intendedBuyersPerCase", work.parameters.buyers());
            results.put("intendedBuyerOperations", work.parameters.intendedOperations());
            long measured = store.attempts(work.id).stream().filter(a -> "MEASURED".equals(a.get("phase"))).count();
            results.put("completion", measured == work.parameters.intendedOperations() && "COMPLETED".equals(state)
                    ? "ALL_BUYERS_DISPATCHED" : "PARTIAL");
            results.put("databaseWork", database.snapshot());
            results.put("caseDatabaseWork", database.caseSnapshots());
            results.put("observations", work.observations);
            results.put("finalReadiness", cache.status());
            results.put("databaseMeasurement", "Actual JDBC execute attempts until finalization, including failures/rollbacks; "
                    + "LISTENER_ADMIN includes background listener calls during the run. Commit/rollback and row decoding excluded.");
            if ("INCONCLUSIVE".equals(state)) { results.put("invariantVerdict", "INCONCLUSIVE"); }
            if ("INCONCLUSIVE".equals(results.get("invariantVerdict")) && "COMPLETED".equals(state)) { state = "INCONCLUSIVE"; }
            store.finish(work.id, state, results, null);
            release(work);
        } catch (RuntimeException failure) {
            LOG.error("Experiment stopped without a success verdict (runId={}, type={})",
                    work.id, failure.getClass().getSimpleName(), failure);
            guard.seal();
            if (guard.inFlight() == 0 && workers.getActiveCount() == 0) {
                try {
                    store.finish(work.id, "FAILED", Map.of("invariantVerdict", "INCONCLUSIVE", "quiescent", true,
                                    "observations", work.observations),
                            "Experiment failed; inspect bounded server logs and persisted attempts.");
                    release(work);
                } catch (RuntimeException persistence) {
                    LOG.error("Run result persistence failed; new runs remain blocked (runId={})", work.id, persistence);
                }
            }
            work.blocked = true;
        } finally { hooks.clear(); }
    }

    private void scenario(Work work) {
        switch (work.parameters.scenario()) {
            case PURCHASE, READ -> {
                boolean purchase = work.parameters.scenario() == RunParameters.Scenario.PURCHASE;
                var fixture = fixture(work, 0, work.parameters.scenario().name(), work.parameters.strategy());
                if (purchase) { admissionSetup(work, fixture); }
                dispatch(work, fixture, purchase, work.parameters.stampedeProtection());
            }
            case COMPARE -> {
                int index = 0;
                for (var strategy : PurchaseStrategy.values()) {
                    if (!canDispatch(work)) { break; }
                    var fixture = fixture(work, index++, strategy.name(), strategy.name());
                    admissionSetup(work, fixture);
                    dispatch(work, fixture, true, true);
                }
                work.observations.put("unsafeMeaning", "NONE can oversell; a nonnegative sample is not a safety guarantee.");
            }
            case STAMPEDE -> {
                for (int index = 0; index < 2 && canDispatch(work); index++) {
                    var fixture = fixture(work, index, index == 0 ? "PROTECTION_ON" : "PROTECTION_OFF", "ATOMIC_SQL");
                    dispatch(work, fixture, false, index == 0);
                }
            }
            case COLD_WARM -> {
                var fixture = fixture(work, 0, "COLD_WARM", "ATOMIC_SQL");
                JsonNode cold = read(work, fixture, 1, "MEASURED");
                JsonNode warm = read(work, fixture, 2, "MEASURED");
                work.observations.put("coldThenWarmObserved", "DATABASE".equals(cold.path("source").asString())
                        && warm.path("source").asString().startsWith("REDIS_CACHE"));
            }
            case LOST_RESPONSE -> {
                var fixture = fixture(work, 0, "LOST_RESPONSE", work.parameters.strategy());
                admissionSetup(work, fixture);
                send(work, fixture, 1, "MEASURED", "POST", purchasePath(fixture), quantity(work), true, true);
                JsonNode retry = send(work, fixture, 1, "RETRY", "POST", purchasePath(fixture), quantity(work), true);
                work.observations.put("fault", "Injected client response discard after real HTTP receipt; not a network outage.");
                work.observations.put("sameKeyReplayObserved", retry.path("replayed").asBoolean());
            }
            case STALE_FILL -> staleFill(work);
            case OUTAGE -> outage(work);
        }
    }

    private RunStore.Fixture fixture(Work work, int index, String label, String strategy) {
        var fixture = store.fixture(work.id, index, label, strategy, work.parameters.stock());
        if (cache.status().readiness() == CacheCoordinator.Readiness.READY
                && switch (work.parameters.scenario()) {
                    case READ, COLD_WARM, STAMPEDE, STALE_FILL -> true;
                    default -> false;
                }) {
            hooks.awaitCreation(fixture.productId());
        }
        store.event(work.id, "CASE_STARTED", fixture);
        return fixture;
    }

    private void admissionSetup(Work work, RunStore.Fixture fixture) {
        if ("REDIS_ASSISTED".equals(fixture.strategy()) && canDispatch(work)) {
            send(work, fixture, 0, "SETUP", "POST", "/demo/stock/" + fixture.productId() + "/reconcile", null, false);
        }
    }

    private boolean canDispatch(Work work) { return !work.cancel.get() && System.nanoTime() < work.deadline; }
    private String purchasePath(RunStore.Fixture fixture) { return "/products/" + fixture.productId() + "/purchase?strategy=" + fixture.strategy(); }
    private String quantity(Work work) { return "{\"quantity\":" + work.parameters.quantity() + "}"; }
    private JsonNode read(Work work, RunStore.Fixture fixture, int buyer, String phase) {
        return send(work, fixture, buyer, phase, "GET", "/products/" + fixture.productId(), null, false);
    }

    private void staleFill(Work work) {
        var fixture = fixture(work, 0, "STALE_FILL", "ATOMIC_SQL");
        if (cache.status().readiness() != CacheCoordinator.Readiness.READY) {
            throw ApiException.unavailable("CACHE_NOT_READY", "Stale-fill demonstration needs a healthy product cache.");
        }
        Future<JsonNode> old;
        try (var pause = hooks.arm(work.id, fixture.productId())) {
            old = workers.submit(() -> {
                try (var scope = guard.local(work.id)) { return read(work, fixture, 1, "MEASURED"); }
            });
            if (!pause.awaitLoaded()) { throw new IllegalStateException("Old reader did not reach its bounded pause."); }
            JsonNode update = send(work, fixture, 0, "SETUP", "PATCH", "/products/" + fixture.productId(),
                    "{\"name\":\"Committed newer run value\"}", false);
            work.observations.put("committedWriter", update);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Stale-read coordinator interrupted.", interrupted);
        }
        try {
            JsonNode stale = old.get(10, TimeUnit.SECONDS);
            JsonNode fresh = read(work, fixture, 2, "VERIFICATION");
            work.observations.put("fault", "Injected bounded pause after actual DB load; writer traverses real PATCH and after-commit invalidation.");
            work.observations.put("stalePublicationRejected", "REJECTED_GENERATION".equals(stale.path("cacheWriteOutcome").asString()));
            work.observations.put("freshRead", fresh);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Stale-read result interrupted.", interrupted);
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException failure) {
            throw new IllegalStateException("Stale-read result did not complete.", failure);
        }
    }

    private void outage(Work work) {
        boolean unavailable;
        try { redis.ping(RedisAccess.Domain.PRODUCT_CACHE); unavailable = false; }
        catch (RedisAccess.Unavailable failure) {
            cache.bypass("Real dependency probe failed during the OUTAGE scenario.");
            unavailable = true;
        }
        work.observations.put("redisCallUnavailableInitially", unavailable);
        work.observations.put("outageMeaning", "Observed actual dependency calls; no shell commands or injected Redis shutdown.");
        var readFixture = fixture(work, 0, "OUTAGE_READ", "ATOMIC_SQL");
        var atomic = fixture(work, 1, "OUTAGE_ATOMIC", "ATOMIC_SQL");
        var admission = fixture(work, 2, "OUTAGE_ADMISSION", "REDIS_ASSISTED");
        read(work, readFixture, 1, "MEASURED");
        send(work, atomic, 1, "MEASURED", "POST", purchasePath(atomic), quantity(work), true);
        admissionSetup(work, admission);
        send(work, admission, 1, "MEASURED", "POST", purchasePath(admission), quantity(work), true);
        if (unavailable) {
            store.event(work.id, "WAITING_FOR_REDIS", Map.of("action",
                    "Start only the project's Redis service from a terminal; recovery remains bounded by the run deadline."));
            while (canDispatch(work) && cache.status().readiness() != CacheCoordinator.Readiness.READY) {
                ProductService.delay(200);
            }
            boolean recovered = canDispatch(work) && cache.status().readiness() == CacheCoordinator.Readiness.READY;
            work.observations.put("recoveryObserved", recovered);
            if (recovered) {
                admissionSetup(work, admission);
                send(work, admission, 1, "RETRY", "POST", purchasePath(admission), quantity(work), true);
                read(work, readFixture, 2, "RECOVERY");
                read(work, readFixture, 3, "RECOVERY");
                store.event(work.id, "RECOVERY_OBSERVED", cache.status());
            }
        } else {
            work.observations.put("demonstration", "Redis was healthy. Stop the project Redis service before a new OUTAGE run.");
        }
    }

    private void dispatch(Work work, RunStore.Fixture fixture, boolean purchase, boolean protection) {
        var completions = new ExecutorCompletionService<Void>(workers);
        var pending = new ArrayList<Future<Void>>();
        Random random = new Random(work.parameters.seed());
        int next = 1;
        int finished = 0;
        while (next <= work.parameters.buyers() || finished < pending.size()) {
            while (next <= work.parameters.buyers() && pending.size() - finished < work.parameters.concurrency()
                    && !work.cancel.get() && System.nanoTime() < work.deadline) {
                int buyer = next++;
                int jitter = random.nextInt(work.parameters.jitterMs() + 1);
                pending.add(completions.submit(() -> {
                    try (var scope = guard.local(work.id)) {
                        ProductService.delay(jitter);
                        if (!work.cancel.get() && System.nanoTime() < work.deadline) {
                            String path = "/products/" + fixture.productId() + (purchase
                                    ? "/purchase?strategy=" + fixture.strategy()
                                    : "?stampedeProtection=" + protection);
                            send(work, fixture, buyer, "MEASURED", purchase ? "POST" : "GET", path,
                                    purchase ? "{\"quantity\":" + work.parameters.quantity() + "}" : null, purchase);
                        }
                        return null;
                    }
                }));
            }
            if (finished == pending.size()) { break; }
            try {
                completions.take().get();
                finished++;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Run coordinator interrupted.", interrupted);
            } catch (java.util.concurrent.ExecutionException failure) {
                work.cancel.set(true);
                // Drain every submitted worker even if one failed to persist its response.
                long drainDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
                for (var task : pending) {
                    long remaining = drainDeadline - System.nanoTime();
                    if (remaining <= 0) { break; }
                    try { task.get(remaining, TimeUnit.NANOSECONDS); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
                    catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException drain) {
                        LOG.warn("Run worker drain issue (type={})", drain.getClass().getSimpleName());
                    }
                }
                throw new IllegalStateException("Run worker failed.", failure.getCause());
            }
        }
    }

    private JsonNode send(Work work, RunStore.Fixture fixture, int buyer, String phase,
                          String method, String path, String body, boolean purchase) {
        return send(work, fixture, buyer, phase, method, path, body, purchase, false);
    }

    private JsonNode send(Work work, RunStore.Fixture fixture, int buyer, String phase,
                          String method, String path, String body, boolean purchase, boolean discard) {
        if (!canDispatch(work)) { return json.createObjectNode().put("code", "DISPATCH_CANCELLED_OR_EXPIRED"); }
        try (var caseScope = database.caseScope(fixture.index())) {
            String client = "r" + work.id.toString().replace("-", "") + "-" + fixture.index() + "-" + buyer;
            String key = "buyer-" + buyer;
            String keyHash = purchase ? PurchaseRequest.parse(fixture.productId(), json.readTree(body),
                    fixture.strategy(), client, key).keyHash() : null;
            long attempt = store.dispatch(work.id, fixture, buyer, phase, client, keyHash, method, path);
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                    .header("X-Lab-Dispatch", work.token).header("X-Client-Id", client)
                    .header("X-Lab-Case", Integer.toString(fixture.index()))
                    .header("Content-Type", "application/json").header("Idempotency-Key", key)
                    .timeout(Duration.ofSeconds(20)).method(method, body == null
                            ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build();
            long started = System.nanoTime();
            try {
                var response = http.send(request, HttpResponse.BodyHandlers.ofString());
                double elapsed = (System.nanoTime() - started) / 1_000_000.0;
                if (discard) {
                    store.response(work.id, attempt, "DISCARDED", null, elapsed,
                            json.valueToTree(Map.of("fault", "Injected client discard after actual HTTP receipt.")));
                    return json.createObjectNode().put("code", "RESPONSE_DISCARDED");
                }
                JsonNode payload = json.readTree(response.body());
                var recorded = json.createObjectNode();
                recorded.set("body", payload);
                recorded.put("requestId", response.headers().firstValue("X-Request-Id").orElse(""));
                recorded.put("runId", response.headers().firstValue("X-Run-Id").orElse(""));
                recorded.put("rateLimit", response.headers().firstValue("X-RateLimit-Status").orElse("NOT_APPLIED"));
                store.response(work.id, attempt, "RESPONSE", response.statusCode(), elapsed, recorded);
                return payload;
            } catch (IOException failure) {
                LOG.warn("Unknown local HTTP outcome (attemptId={}, type={})", attempt, failure.getClass().getSimpleName());
                store.response(work.id, attempt, "UNKNOWN", null, (System.nanoTime() - started) / 1_000_000.0,
                        json.valueToTree(Map.of("error", failure.getClass().getSimpleName())));
                return json.createObjectNode().put("code", "HTTP_UNKNOWN");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("HTTP worker interrupted; outcome unknown.", interrupted);
            }
        }
    }

    private synchronized void release(Work work) {
        guard.finish(); database.end(); if (active == work) { active = null; }
    }

    public void requireDemo() {
        if (!lab.demoEnabled()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "DEMO_DISABLED", "Experiments require demo/benchmark mode.", false);
        }
    }

    private static ThreadPoolExecutor pool(int threads, int queue, String name) {
        var executor = new ThreadPoolExecutor(threads, threads, 10, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(queue), task -> {
                    var thread = new Thread(task, name);
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    @PreDestroy
    public void stop() {
        synchronized (this) {
            stopping = true;
            Work work = active;
            if (work != null) { work.cancel.set(true); guard.seal(); }
            coordinator.shutdown();
        }
        workers.shutdown();
        try {
            if (!coordinator.awaitTermination(35, TimeUnit.SECONDS)) {
                LOG.error("Runner shutdown exceeded drain bound; persisted active run will be interrupted on restart.");
            }
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        transport.shutdown();
    }
}
