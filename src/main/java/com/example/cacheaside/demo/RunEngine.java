package com.example.cacheaside.demo;

import com.example.cacheaside.product.ProductService;
import com.example.cacheaside.purchase.PurchaseRequest;
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

    private static final class Work {
        final UUID id;
        final String token;
        final RunParameters parameters;
        final long started = System.nanoTime();
        final long deadline;
        final AtomicBoolean cancel = new AtomicBoolean();
        Work(UUID id, String token, RunParameters parameters) {
            this.id = id; this.token = token; this.parameters = parameters;
            deadline = started + TimeUnit.SECONDS.toNanos(parameters.durationSeconds());
        }
    }

    public RunEngine(RunStore store, RunGuard guard, LabProperties lab, JsonMapper json) {
        this.store = store; this.guard = guard; this.lab = lab; this.json = json;
    }

    @EventListener
    public void port(WebServerInitializedEvent event) { port = event.getWebServer().getPort(); }

    public synchronized Map<String, Object> start(RunParameters parameters) {
        requireDemo();
        if (stopping || port == 0) { throw ApiException.unavailable("RUN_UNAVAILABLE", "Runner is not accepting work."); }
        UUID id = UUID.randomUUID();
        String token = guard.begin(id, parameters.durationSeconds());
        var work = new Work(id, token, parameters);
        try {
            store.create(id, parameters, Map.of("java", System.getProperty("java.version"),
                    "os", System.getProperty("os.name"), "appVersion", "0.4.0",
                    "readDelayMs", lab.readDelayMs(), "purchaseDelayMs", lab.purchaseDelayMs(),
                    "initialCache", "COLD_NEW_FIXTURE", "seedMeaning", "Jitter only; OS/DB scheduling is not deterministic"));
            active = work;
            coordinator.execute(() -> execute(work));
            return store.snapshot(id);
        } catch (RuntimeException failure) {
            if (active == null) { guard.finish(); }
            throw failure;
        }
    }

    public Map<String, Object> cancel(UUID id) {
        requireDemo();
        Work work = active;
        if (work != null && work.id.equals(id)) {
            work.cancel.set(true);
            store.state(id, "DRAINING");
        }
        return store.snapshot(id);
    }

    private void execute(Work work) {
        try (var scope = guard.local(work.id)) {
            store.state(work.id, "RUNNING");
            var fixture = store.fixture(work.id, 0, work.parameters.scenario().name(),
                    work.parameters.strategy(), work.parameters.stock());
            if ("REDIS_ASSISTED".equals(fixture.strategy()) && work.parameters.scenario() == RunParameters.Scenario.PURCHASE) {
                send(work, fixture, 0, "SETUP", "POST", "/demo/stock/" + fixture.productId() + "/reconcile", null, false);
            }
            dispatch(work, fixture);
            guard.seal();
            store.state(work.id, "DRAINING");
            long drainDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (guard.inFlight() > 0 && System.nanoTime() < drainDeadline) { ProductService.delay(20); }
            if (guard.inFlight() != 0) {
                throw new IllegalStateException("Server requests exceeded the drain bound; runner remains fenced.");
            }
            String state = work.cancel.get() ? "CANCELLED" : System.nanoTime() >= work.deadline ? "INCONCLUSIVE" : "COMPLETED";
            var results = new LinkedHashMap<String, Object>();
            results.put("quiescent", true);
            results.put("elapsedMs", (System.nanoTime() - work.started) / 1_000_000.0);
            results.put("intendedBuyers", work.parameters.buyers());
            results.put("invariantVerdict", "NOT_EVALUATED");
            results.put("note", "HTTP results persisted separately from inventory correctness.");
            store.finish(work.id, state, results, null);
            release(work);
        } catch (RuntimeException failure) {
            LOG.error("Experiment stopped without a success verdict (runId={}, type={})",
                    work.id, failure.getClass().getSimpleName(), failure);
            guard.seal();
            if (guard.inFlight() == 0) {
                try {
                    store.finish(work.id, "FAILED", Map.of("invariantVerdict", "INCONCLUSIVE", "quiescent", true),
                            "Experiment failed; inspect bounded server logs and persisted attempts.");
                    release(work);
                } catch (RuntimeException persistence) {
                    LOG.error("Run result persistence failed; new runs remain blocked (runId={})", work.id, persistence);
                }
            }
        }
    }

    private void dispatch(Work work, RunStore.Fixture fixture) {
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
                            boolean purchase = work.parameters.scenario() == RunParameters.Scenario.PURCHASE;
                            String path = "/products/" + fixture.productId() + (purchase
                                    ? "/purchase?strategy=" + fixture.strategy()
                                    : "?stampedeProtection=" + work.parameters.stampedeProtection());
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
                for (var task : pending) {
                    try { task.get(30, TimeUnit.SECONDS); }
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
        String client = "r" + work.id.toString().replace("-", "") + "-" + fixture.index() + "-" + buyer;
        String key = "buyer-" + buyer;
        String keyHash = purchase ? PurchaseRequest.parse(fixture.productId(), json.readTree(body),
                fixture.strategy(), client, key).keyHash() : null;
        long attempt = store.dispatch(work.id, fixture, buyer, phase, client, keyHash, method, path);
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("X-Lab-Dispatch", work.token).header("X-Client-Id", client)
                .header("Content-Type", "application/json").header("Idempotency-Key", key)
                .timeout(Duration.ofSeconds(20)).method(method, body == null
                        ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build();
        long started = System.nanoTime();
        try {
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode payload = json.readTree(response.body());
            var recorded = json.createObjectNode();
            recorded.set("body", payload);
            recorded.put("requestId", response.headers().firstValue("X-Request-Id").orElse(""));
            recorded.put("runId", response.headers().firstValue("X-Run-Id").orElse(""));
            recorded.put("rateLimit", response.headers().firstValue("X-RateLimit-Status").orElse("NOT_APPLIED"));
            store.response(work.id, attempt, "RESPONSE", response.statusCode(),
                    (System.nanoTime() - started) / 1_000_000.0, recorded);
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

    private synchronized void release(Work work) { guard.finish(); if (active == work) { active = null; } }

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
        stopping = true;
        Work work = active;
        if (work != null) { work.cancel.set(true); }
        coordinator.shutdown();
        workers.shutdown();
        try {
            if (!coordinator.awaitTermination(35, TimeUnit.SECONDS)) {
                LOG.error("Runner shutdown exceeded drain bound; persisted active run will be interrupted on restart.");
            }
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        transport.shutdown();
    }
}
