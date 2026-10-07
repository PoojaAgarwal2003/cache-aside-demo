# Repeatable benchmarks

These are bounded local observations, not capacity tests or service SLAs.
Node 22+ is development-only orchestration; it is not needed to run the app.
No npm dependencies are required by the benchmark client. PostgreSQL remains
the correctness authority; all load uses the existing persisted HTTP runner.

```powershell
# Terminal 1, with real Compose dependencies:
.\scripts\start.ps1 -Profile benchmark
# Terminal 2, Windows PowerShell 5.1 or PowerShell 7:
.\scripts\benchmark.ps1 -Warmups 2 -Trials 5 -OutDirectory benchmark-results\comparison
.\scripts\benchmark.ps1 -Scenario COLD_WARM -OutDirectory benchmark-results\reads
```

```sh
./scripts/start.sh benchmark
# Another terminal:
./scripts/benchmark.sh --warmups 2 --trials 5 --out benchmark-results/comparison
./scripts/benchmark.sh --scenario STAMPEDE --out benchmark-results/stampede
```

For explicit sizes, use the same portable client from either shell:
`node scripts/benchmark.mjs --buyers 50 --concurrency 10 --stock 10 --quantity 1 --seed 1`.
Only `APP_PORT` selects a target, always `127.0.0.1`; there is no arbitrary URL.
PowerShell/Bash wrappers load the same allowlisted `.env` as start scripts.
The output directory must not already exist; each default invocation generates
a fresh directory. Runs and fixture data are retained, not reset between trials.

The client **requires the benchmark profile, cache READY, and both delays zero**.
It rechecks recorded delays on every export. Warmups (1-5, default 2) are whole
runs in the same JVM, excluded from every aggregate. Measured trials (2-20,
default 5) each create fresh cold fixtures/client identities. This warms JVM/
connection machinery, not the next trial's product cache. READ starts cold;
COLD_WARM records the actual cold/warm sources in each two-request run.
COMPARE visits five independent cases in a fixed order; ordering, JIT, GC,
concurrent host load and OS/DB scheduling can bias results even with a seed.
NONE is unsafe whether or not this sample oversells. No barrier or synthetic
delay is inserted into benchmark traffic to manufacture an unsafe observation.

`benchmark.json` contains exact parameters, warmup/measured run IDs, application/
PostgreSQL/JVM information, CPU model, logical CPU count, RAM, OS and client
runtime (no hostname, username or personal paths), per-trial outcomes and pooled
raw-attempt percentiles per case. Sibling `<runId>.json` files are the unchanged
backend exports, including attempts, ledger reconciliation, delays, source
classification, SQL work and actual measurement intervals. Redis image/version
and deployment topology belong in the accompanying operating evidence; the
client does not invent a Redis version from a dependency pin.

Percentiles use nearest rank `sorted[ceil(p*N)-1]`, with sample count, milliseconds
and null for empty samples. 2xx samples are separate from all attempts; setup/
recovery probes are excluded. Pool raw observations, **never average percentiles**.
With fewer than 100 samples p99 is the maximum; even larger correlated local
samples do not justify confidence intervals or an SLA. Per-trial throughput
retains the backend's run-wide denominator, not isolated strategy time. Do not
sum it across cases or compare it to a scripted-delay demo as a speedup.

Completion, liveness/errors and inventory safety remain separate. A protected
FAIL or incomplete trial fails the command and retains evidence; NONE's expected
failure does not invalidate a comparison. Errors/unknown HTTP outcomes remain
visible even when the committed ledger is safe. Ctrl+C requests cancellation/
drain of only the active owned run. If that cannot complete, the manifest records
the run ID and cleanup error for manual inspection/reconciliation, never a PASS.
An ambiguously accepted POST cannot be retried automatically: inspect recent
runs before creating more load. No process or container is killed by this tool.
