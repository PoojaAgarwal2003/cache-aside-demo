package com.example.cacheaside.purchase;

import com.example.cacheaside.web.ApiException;
import com.example.cacheaside.web.LabProperties;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class StockAdmissionService {
    private static final Logger LOG = LoggerFactory.getLogger(StockAdmissionService.class);
    private final JdbcTemplate jdbc;
    private final RedisStockClient redis;
    private final TransactionTemplate independent;
    private final FixtureActivity activity;
    private final LabProperties properties;
    private final Semaphore buyers = new Semaphore(4, true);

    public StockAdmissionService(JdbcTemplate jdbc, RedisStockClient redis, PlatformTransactionManager manager,
                                 FixtureActivity activity, LabProperties properties) {
        this.jdbc = jdbc;
        this.redis = redis;
        this.activity = activity;
        this.properties = properties;
        independent = new TransactionTemplate(manager);
        independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        independent.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        independent.setTimeout(5);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void distrustAfterRestart() {
        jdbc.update("UPDATE stock_admission_epochs SET trusted=false WHERE trusted");
    }

    <T> T admittedWork(Supplier<T> work) {
        boolean acquired;
        try {
            acquired = buyers.tryAcquire(2, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw ApiException.unavailable("INTERRUPTED", "Admission wait was interrupted.");
        }
        if (!acquired) {
            throw ApiException.unavailable("ADMISSION_OVERLOADED", "The bounded admission workers are busy.");
        }
        try {
            return work.get();
        } finally {
            buyers.release();
        }
    }

    Reservation reserve(PurchaseRequest request) {
        var epochs = jdbc.query("SELECT epoch FROM stock_admission_epochs WHERE product_id=? AND trusted",
                (row, index) -> row.getObject(1, UUID.class), request.productId());
        if (epochs.isEmpty()) {
            throw ApiException.unavailable("ADMISSION_UNTRUSTED",
                    "Drain and reconcile this fixture before Redis-assisted purchases.");
        }
        var reservation = new Reservation(UUID.randomUUID(), request.productId(), epochs.get(0),
                request.clientId(), request.keyHash(), request.quantity());
        independent.executeWithoutResult(status -> {
            deadlines();
            jdbc.update("""
                    INSERT INTO stock_reservations(reservation_id,product_id,epoch,client_id,key_hash,quantity)
                    VALUES (?,?,?,?,?,?)
                    """, reservation.id(), reservation.productId(), reservation.epoch(),
                    reservation.clientId(), reservation.keyHash(), reservation.quantity());
        });
        // The journal and owning DB claim both exist before any Redis mutation.
        String result = redis.reserve(reservation);
        if ("REJECTED".equals(result)) {
            return null;
        }
        if (!"RESERVED".equals(result) && !"HELD".equals(result)) {
            throw ApiException.unavailable("ADMISSION_UNTRUSTED",
                    "Redis counter cannot admit this request (" + result + "); drain and reconcile.");
        }
        jdbc.update("UPDATE purchase_requests SET reservation_id=? WHERE client_id=? AND key_hash=?",
                reservation.id(), request.clientId(), request.keyHash());
        return reservation;
    }

    // Used by both the purchase and reconciliation transaction before resolving a
    // scoped key. Hash collisions only serialize unrelated keys, never merge them.
    void lockRequest(String clientId, String keyHash) {
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", clientId + ":" + keyHash);
    }

    void settleRequest(PurchaseRequest request) {
        try {
            var reservations = pending("client_id=? AND key_hash=?", request.clientId(), request.keyHash());
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            for (var reservation : reservations) {
                if (System.nanoTime() >= deadline) {
                    LOG.warn("Reservation cleanup deadline reached (productId={}); reconciliation remains pending",
                            request.productId());
                    break;
                }
                resolve(reservation);
            }
        } catch (ApiException | DataAccessException | TransactionException failure) {
            // Never replace a committed SOLD response with a cleanup failure.
            LOG.warn("Reservation reconciliation pending (productId={}, type={}); inspect the durable journal",
                    request.productId(), failure.getClass().getSimpleName());
        }
    }

    private void resolve(Reservation reservation) {
        independent.executeWithoutResult(status -> {
            deadlines();
            lockRequest(reservation.clientId(), reservation.keyHash());
            var states = jdbc.queryForList("SELECT state FROM stock_reservations WHERE reservation_id=? FOR UPDATE",
                    String.class, reservation.id());
            if (states.isEmpty() || !"PENDING".equals(states.get(0))) {
                return;
            }
            boolean committed = Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT EXISTS(SELECT 1 FROM purchase_requests
                    WHERE client_id=? AND key_hash=? AND outcome='SOLD' AND reservation_id=?)
                    """, Boolean.class, reservation.clientId(), reservation.keyHash(), reservation.id()));
            // Lock acquisition proved the old inventory transaction is over.
            // If the DB is unavailable/lock wait is unknown, no release occurs.
            String resolution = committed ? "COMMITTED" : redis.release(reservation);
            if (!List.of("COMMITTED", "RELEASED", "NOT_RESERVED", "STALE_EPOCH", "EXPIRED").contains(resolution)) {
                throw ApiException.unavailable("RECONCILIATION_PENDING",
                        "Reservation could not be safely resolved (" + resolution + ").");
            }
            jdbc.update("UPDATE stock_reservations SET state=?,resolved_at=clock_timestamp() WHERE reservation_id=?",
                    resolution, reservation.id());
        });
    }

    public Status inspect(long productId) {
        requireDemo(productId);
        var stocks = jdbc.queryForList("SELECT stock FROM products WHERE id=?", Integer.class, productId);
        if (stocks.isEmpty()) {
            throw ApiException.notFound();
        }
        var epochs = jdbc.query("SELECT epoch,trusted FROM stock_admission_epochs WHERE product_id=?",
                (row, index) -> new Epoch(row.getObject(1, UUID.class), row.getBoolean(2)), productId);
        var unresolved = jdbc.queryForObject(
                "SELECT count(*) FROM stock_reservations WHERE product_id=? AND state='PENDING'", Integer.class, productId);
        var snapshot = redis.inspect(productId);
        Epoch epoch = epochs.isEmpty() ? null : epochs.get(0);
        boolean trusted = epoch != null && epoch.trusted() && epoch.id().equals(snapshot.epoch())
                && "PRESENT".equals(snapshot.presence());
        return new Status(productId, stocks.get(0), epoch == null ? null : epoch.id(),
                trusted, snapshot, Objects.requireNonNull(unresolved),
                "Advisory only. Reset after drain; PostgreSQL conditional UPDATE decides every sale.");
    }

    public Status reconcile(long productId) {
        requireDemo(productId);
        return activity.maintenance(productId, () -> {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            for (var reservation : pending("product_id=?", productId)) {
                if (System.nanoTime() >= deadline) {
                    throw ApiException.unavailable("RECONCILIATION_PENDING", "Reconciliation deadline reached; retry.");
                }
                resolve(reservation);
            }
            if (jdbc.queryForObject("SELECT count(*) FROM stock_reservations WHERE product_id=? AND state='PENDING'",
                    Integer.class, productId) != 0) {
                throw ApiException.unavailable("RECONCILIATION_PENDING",
                        "More reservations remain; repeat reconciliation before resetting.");
            }
            independent.executeWithoutResult(status -> {
                deadlines();
                var stocks = jdbc.queryForList("SELECT stock FROM products WHERE id=? FOR UPDATE", Integer.class, productId);
                if (stocks.isEmpty()) {
                    throw ApiException.notFound();
                }
                if (stocks.get(0) < 0) {
                    throw new ApiException(HttpStatus.CONFLICT, "INVALID_FIXTURE",
                            "Cannot initialize admission from negative inventory.", false);
                }
                UUID epoch = UUID.randomUUID();
                jdbc.update("""
                        INSERT INTO stock_admission_epochs(product_id,epoch,trusted) VALUES (?,?,true)
                        ON CONFLICT (product_id) DO UPDATE SET epoch=EXCLUDED.epoch,trusted=true
                        """, productId, epoch);
                redis.reset(productId, epoch, stocks.get(0));
                // If commit is unknown, the new Redis epoch must match the
                // committed DB epoch before it can ever admit a future buyer.
            });
            return inspect(productId);
        });
    }

    private List<Reservation> pending(String predicate, Object... arguments) {
        return jdbc.query("""
                SELECT reservation_id,product_id,epoch,client_id,key_hash,quantity
                FROM stock_reservations WHERE state='PENDING' AND
                """ + predicate + " ORDER BY created_at LIMIT 100",
                (row, index) -> new Reservation(row.getObject(1, UUID.class), row.getLong(2),
                        row.getObject(3, UUID.class), row.getString(4), row.getString(5), row.getInt(6)), arguments);
    }

    private void deadlines() {
        jdbc.execute("SET LOCAL lock_timeout='2s'");
        jdbc.execute("SET LOCAL statement_timeout='3s'");
    }

    private void requireDemo(long productId) {
        if (!properties.demoEnabled()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "DEMO_DISABLED", "Admission diagnostics are demo-only.", false);
        }
        if (productId <= 0) {
            throw ApiException.invalid("Product id must be positive.");
        }
    }

    public record Reservation(UUID id, long productId, UUID epoch, String clientId, String keyHash, int quantity) { }
    private record Epoch(UUID id, boolean trusted) { }
    public record Status(long productId, int databaseStock, UUID databaseEpoch, boolean trusted,
                         RedisStockClient.Snapshot redis, int unresolvedReservations, String meaning) { }
}
