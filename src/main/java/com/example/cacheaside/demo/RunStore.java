package com.example.cacheaside.demo;

import com.example.cacheaside.web.ApiException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class RunStore {
    public record Fixture(int index, long productId, String label, String strategy, int initialStock, long initialVersion) { }
    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    private final TransactionTemplate transaction;
    private final RunGuard guard;
    private final RunHooks hooks;

    public RunStore(JdbcTemplate jdbc, JsonMapper json,     PlatformTransactionManager manager, RunGuard guard, RunHooks hooks) {
        this.jdbc = jdbc; this.json = json; this.guard = guard;
        this.hooks = hooks;
        transaction = new TransactionTemplate(manager);
        transaction.setTimeout(5);
    }

    public void create(UUID id, RunParameters parameters, Map<String, Object> environment) {
        jdbc.update("INSERT INTO demo_runs(run_id,state,parameters,environment) VALUES (?,'STARTING',?::jsonb,?::jsonb)",
                id, json.writeValueAsString(parameters), json.writeValueAsString(environment));
    }

    public Fixture fixture(UUID run, int index, String label, String strategy, int stock) {
        return transaction.execute(status -> {
            long product = jdbc.queryForObject(
                    "INSERT INTO products(name,price,stock) VALUES (?,1,?) RETURNING id",
                    Long.class, "Run " + run + " / " + label, stock);
            jdbc.update("""
                    INSERT INTO demo_run_fixtures(run_id,case_index,product_id,label,strategy,initial_stock,initial_version)
                    VALUES (?,?,?,?,?,?,0)
                    """, run, index, product, label, strategy, stock);
            guard.own(product);
            hooks.expectCreation(product);
            return new Fixture(index, product, label, strategy, stock, 0);
        });
    }

    public long dispatch(UUID run, Fixture fixture, int buyer, String phase, String client,
                         String keyHash, String method, String path) {
        return jdbc.queryForObject("""
                INSERT INTO demo_run_attempts(run_id,case_index,buyer,phase,client_id,key_hash,method,path)
                VALUES (?,?,?,?,?,?,?,?) RETURNING attempt_id
                """, Long.class, run, fixture.index(), buyer, phase, client, keyHash, method, path);
    }

    public void response(UUID run, long attempt, String state, Integer status, double elapsed, JsonNode body) {
        transaction.executeWithoutResult(tx -> {
            jdbc.update("""
                    UPDATE demo_run_attempts SET state=?,http_status=?,elapsed_ms=?,response=?::jsonb WHERE attempt_id=? AND run_id=?
                    """, state, status, elapsed, json.writeValueAsString(body), attempt, run);
            event(run, "HTTP_RESULT", Map.of("attemptId", attempt, "state", state));
        });
    }

    public void state(UUID run, String state) {
        jdbc.update("UPDATE demo_runs SET state=? WHERE run_id=? AND active", state, run);
        event(run, state, Map.of());
    }

    public void event(UUID run, String kind, Object detail) {
        transaction.executeWithoutResult(tx -> {
            jdbc.queryForList("SELECT run_id FROM demo_runs WHERE run_id=? FOR UPDATE", run);
            jdbc.update("UPDATE demo_runs SET events_created=events_created+1 WHERE run_id=?", run);
            jdbc.update("INSERT INTO demo_run_events(run_id,kind,detail) VALUES (?,?,?::jsonb)",
                    run, kind, json.writeValueAsString(detail));
            jdbc.update("""
                    DELETE FROM demo_run_events WHERE run_id=? AND sequence <
                    (SELECT sequence FROM demo_run_events WHERE run_id=? ORDER BY sequence DESC OFFSET 255 LIMIT 1)
                    """, run, run);
        });
    }

    public void finish(UUID run, String state, Object result, String error) {
        transaction.executeWithoutResult(tx -> {
            jdbc.update("""
                    UPDATE demo_runs SET state=?,result=?::jsonb,error=?,ended_at=clock_timestamp(),active=false
                    WHERE run_id=? AND active
                    """, state, json.writeValueAsString(result), error, run);
            event(run, state, Map.of("resultPersisted", true));
        });
    }

    public List<Fixture> fixtures(UUID run) {
        return jdbc.query("""
                SELECT * FROM demo_run_fixtures WHERE run_id=? ORDER BY case_index
                """, (row, i) -> new Fixture(row.getInt("case_index"), row.getLong("product_id"),
                row.getString("label"), row.getString("strategy"), row.getInt("initial_stock"),
                row.getLong("initial_version")), run);
    }

    public List<Map<String, Object>> attempts(UUID run) {
        return jdbc.query("""
                SELECT * FROM demo_run_attempts WHERE run_id=? ORDER BY attempt_id LIMIT 1200
                """, (row, i) -> {
            var result = new LinkedHashMap<String, Object>();
            result.put("attemptId", row.getLong("attempt_id"));
            result.put("caseIndex", row.getInt("case_index")); result.put("buyer", row.getInt("buyer"));
            result.put("phase", row.getString("phase")); result.put("clientId", row.getString("client_id"));
            result.put("keyHash", row.getString("key_hash")); result.put("method", row.getString("method"));
            result.put("path", row.getString("path")); result.put("state", row.getString("state"));
            result.put("httpStatus", row.getObject("http_status")); result.put("elapsedMs", row.getObject("elapsed_ms"));
            result.put("response", row.getString("response") == null ? null : json.readTree(row.getString("response")));
            return result;
        }, run);
    }

    public Map<String, Object> snapshot(UUID run) {
        var rows = jdbc.query("SELECT * FROM demo_runs WHERE run_id=?", (row, i) -> {
            var result = new LinkedHashMap<String, Object>();
            result.put("runId", run); result.put("state", row.getString("state")); result.put("active", row.getBoolean("active"));
            result.put("parameters", json.readTree(row.getString("parameters")));
            result.put("environment", json.readTree(row.getString("environment")));
            result.put("createdAt", row.getTimestamp("created_at").toInstant());
            result.put("endedAt", row.getTimestamp("ended_at") == null ? null : row.getTimestamp("ended_at").toInstant());
            result.put("result", row.getString("result") == null ? null : json.readTree(row.getString("result")));
            result.put("error", row.getString("error")); return result;
        }, run);
        if (rows.isEmpty()) { throw new ApiException(HttpStatus.NOT_FOUND, "RUN_NOT_FOUND", "Experiment not found.", false); }
        var result = rows.get(0);
        result.put("fixtures", fixtures(run));
        result.put("httpAttempts", jdbc.queryForObject("SELECT count(*) FROM demo_run_attempts WHERE run_id=?", Integer.class, run));
        result.put("httpResponses", jdbc.queryForObject(
                "SELECT count(*) FROM demo_run_attempts WHERE run_id=? AND state='RESPONSE'", Integer.class, run));
        if ((boolean) result.get("active")) {
            result.put("liveInventory", jdbc.queryForList("""
                    SELECT f.case_index AS "caseIndex",p.stock,p.version,
                    (SELECT count(*) FROM purchase_ledger l WHERE l.product_id=f.product_id) AS "uniqueSales",
                    (SELECT coalesce(sum(quantity),0) FROM purchase_ledger l WHERE l.product_id=f.product_id) AS "soldQuantity",
                    (SELECT coalesce(sum(attempts-1),0) FROM purchase_requests p
                     WHERE p.product_id=f.product_id) AS "transactionRetries"
                    FROM demo_run_fixtures f LEFT JOIN products p ON p.id=f.product_id WHERE f.run_id=? ORDER BY f.case_index
                    """, run));
            result.put("liveMeaning", "Unquiesced observation, not a final invariant verdict.");
        }
        return result;
    }

    public List<Map<String, Object>> recent() {
        return jdbc.queryForList("""
                SELECT run_id AS "runId",state,created_at AS "createdAt",ended_at AS "endedAt"
                FROM demo_runs ORDER BY created_at DESC LIMIT 20
                """);
    }

    public List<UUID> unfinished() {
        return jdbc.query("SELECT run_id FROM demo_runs WHERE active", (row, i) -> row.getObject(1, UUID.class));
    }

    public Map<String, Object> events(UUID run, long after, int limit) {
        snapshot(run);
        var events = jdbc.query("""
                SELECT * FROM demo_run_events WHERE run_id=? AND sequence>? ORDER BY sequence LIMIT ?
                """, (row, i) -> Map.<String, Object>of("sequence", row.getLong("sequence"),
                "kind", row.getString("kind"), "detail", json.readTree(row.getString("detail")),
                "at", row.getTimestamp("created_at").toInstant()), run, after, limit);
        Long first = jdbc.queryForObject("SELECT min(sequence) FROM demo_run_events WHERE run_id=?", Long.class, run);
        long retained = jdbc.queryForObject("SELECT count(*) FROM demo_run_events WHERE run_id=?", Long.class, run);
        long generated = jdbc.queryForObject("SELECT events_created FROM demo_runs WHERE run_id=?", Long.class, run);
        long cursor = events.isEmpty() ? after : (long) events.get(events.size() - 1).get("sequence");
        return Map.of("events", events, "nextCursor", cursor, "earliestCursor", first == null ? 0 : first,
                "gap", first != null && after > 0 && after < first - 1, "retentionLimit", 256,
                "truncated", generated > retained, "droppedEvents", generated - retained,
                "hasMore", jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM demo_run_events WHERE run_id=? AND sequence>?)",
                        Boolean.class, run, cursor));
    }
}
