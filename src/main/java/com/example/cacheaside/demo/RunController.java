package com.example.cacheaside.demo;

import com.example.cacheaside.web.ApiException;
import java.net.URI;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/demo/runs")
public class RunController {
    private final RunEngine engine;
    private final RunStore store;
    public RunController(RunEngine engine, RunStore store) { this.engine = engine; this.store = store; }

    @PostMapping
    public ResponseEntity<Map<String, Object>> start(@RequestBody JsonNode parameters) {
        var result = engine.start(RunParameters.parse(parameters));
        return ResponseEntity.accepted().location(URI.create("/demo/runs/" + result.get("runId"))).body(result);
    }
    @GetMapping
    public Object recent() { engine.requireDemo(); return store.recent(); }
    @GetMapping("/{id}")
    public Object snapshot(@PathVariable UUID id) { engine.requireDemo(); return store.snapshot(id); }
    @PostMapping("/{id}/cancel")
    public Object cancel(@PathVariable UUID id) { return engine.cancel(id); }
    @GetMapping("/{id}/events")
    public Object events(@PathVariable UUID id, @RequestParam(defaultValue = "0") long after,
                         @RequestParam(defaultValue = "100") int limit) {
        engine.requireDemo();
        if (after < 0 || limit < 1 || limit > 100) { throw ApiException.invalid("Cursor must be nonnegative; page size 1-100."); }
        return store.events(id, after, limit);
    }
    @GetMapping("/{id}/export")
    public Object export(@PathVariable UUID id) {
        engine.requireDemo();
        return Map.of("schemaVersion", 1, "run", store.snapshot(id), "attempts", store.attempts(id),
                "events", store.events(id, 0, 100));
    }
}
