# Persisted experiments

Milestone 4 provides bounded HTTP dispatch, durable evidence, inventory
verification, restart interruption, guided scenarios and command-line clients.
The dashboard is milestone 5; this is the same backend it will use.

Healthy-cache read scenarios wait up to three seconds for their own fixture's
committed creation notification before measuring. This drains setup invalidation
without guessing a sleep duration or counting setup as a cache miss.

`POST /demo/runs` accepts a JSON object. Defaults are scenario `PURCHASE`,
strategy `ATOMIC_SQL`, 50 buyers, concurrency 10, stock 10, quantity 1, seed 1,
zero jitter, 60 seconds, and stampede protection enabled. `READ` uses the same
dispatcher. Unknown fields and user-supplied target hosts are rejected.

Bounds: buyers 1-100, concurrency 1-50 and no greater than buyers, quantity
1-1000, stock 0-1000000, jitter 0-100 milliseconds, duration 1-120 seconds.
Jitter is seeded; OS, transaction and network scheduling are not deterministic.
Configured read/purchase synthetic delays are recorded in each run, not counted
as SQL execution time. Comparison/stampede caps apply per case; one comparison
has at most five products and 500 buyers total. No automatic retry loop expands
the number of logical buyers.
The dispatcher has separate bounded coordinator, HTTP worker and transport
executors. Actual GET/purchase requests go to this server's discovered loopback
port, traverse its normal request boundary, limiter and controllers, and never
follow redirects or use system proxies.

One active run owns newly created isolated products and fresh client identities.
Other runs, fixture reads/mutations and global cache clear receive 409 while it
is active. Direct SQL remains an administrator escape hatch, not a protected
API. Internal dispatch authorization is process-local and never exported.
Only hashes of scoped idempotency keys are persisted in run evidence.

Read `GET /demo/runs/{runId}` for state/parameters/fixtures and HTTP totals.
`GET /demo/runs` returns the latest 20 runs. `POST /demo/runs/{runId}/cancel`
stops additional dispatch, not already accepted transactions.
`GET /demo/runs/{runId}/events?after=0&limit=100` is cursor-based.
`GET /demo/runs/{runId}/export` returns durable JSON, including attempts and
their request IDs, HTTP status and actual payloads.

## Drain, accounting and interruption

Cancellation seals internal dispatch immediately. Already accepted server
requests finish before fixture ownership is released; client futures alone are
not proof of server quiescence. Purchase retries also check the run's monotonic
deadline. Dispatch is bounded to 120 seconds, with an additional 30-second drain
budget and bounded verification. If drain or result persistence fails, the
runner remains fenced rather than allowing a second run over unresolved work.

When `finalizationBlocked` is true, restore the failed dependency and
`POST /demo/runs/{id}/reconcile`. It refuses while workers/server requests remain,
then retries key-only accounting without dispatching purchases. The recovered
result stays INCONCLUSIVE; it is not retroactively a clean experiment.

Verification takes the same transaction-scoped advisory locks used by purchases,
then reads the committed request/ledger rows and locks final product rows.
It reports unique sales and **sold quantity** independently of HTTP counts.
Each case checks `sold quantity + final stock = initial stock`, nonnegative
stock, known scoped keys, at-most-once purchase IDs and that every reported sale
exists in the ledger. NONE remains explicitly unsafe even if one sample happens
not to oversell. Pending admission reservations yield INCONCLUSIVE, not PASS.
Safety verdict, HTTP errors/rejections and dispatch completion are separate.

An unknown HTTP outcome is resolved by its persisted client/key hash after the
dispatch seal, server drain and transaction fence. A missing committed row then
means no committed purchase, not permission to silently issue a new purchase.
On process restart, unfinished runs become INTERRUPTED; key-fenced committed
outcomes are exported without replaying any purchase. Their verdict remains
INCONCLUSIVE. Pre-crash in-memory timing/counters are labeled unavailable.
Final result and terminal state are committed in one transaction.

Events are serialized per run, retain at most 256 rows, and expose an exact
dropped-event count, earliest cursor, gap/truncation flags and next cursor.
Cursor pages contain at most 100 events. Truncation never removes attempt
records or authoritative final results.

## Measurement definitions

HTTP attempt latency uses `System.nanoTime`, from send until response receipt or
transport failure. Percentiles use nearest rank (`ceil(p * N) - 1` in sorted
samples), include sample count and return null for empty samples. Successful
2xx latency is separate from all-attempt latency. Business rejections, errors,
unknown outcomes and transaction retries are explicit. Whole-run throughput
includes harness setup and drain; it is not a service SLA or isolated SQL speed.

`databaseWork` counts actual JDBC `execute*` calls by USER_READ, FALLBACK,
PURCHASE, LISTENER_ADMIN and VERIFICATION, including failures and transactions
that later roll back. SELECT (including advisory-lock calls) is a read;
INSERT/UPDATE/DELETE is a write; SET/LISTEN is control work. One batch execution
is one call. Execution time includes JDBC/network/lock wait, not row decoding,
transaction commit/rollback or synthetic sleeps. The owned listener's direct
JDBC connection is counted explicitly during the run window. Other unrelated
requests are excluded. Counts stop before the final persistence transaction.
`caseDatabaseWork` additionally attributes actual dispatch/controller calls to
the case; listener and final global verification remain in the overall totals.
No cache-miss arithmetic or SQL-log scraping is used as a query counter.
Measured HTTP metrics include MEASURED and RETRY phases; setup, stale-reader
verification and outage recovery probes remain separately labeled in raw
attempts. Per-case throughput uses the whole-run denominator, not an invented
per-case service interval. Each HTTP summary includes its exact
`measurementWindowMs`: case intervals stop before final ledger verification,
while the normal final global summary includes that verification. An interrupted
run cannot retain a monotonic clock across JVMs and exports null duration and
throughput rather than pretending no work occurred.

## Scenarios

| Scenario | Actual work and interpretation |
|---|---|
| PURCHASE | One isolated product, selected strategy, bounded distinct-client buyers |
| READ | One cold isolated product, actual GETs; optional `stampedeProtection:false` keeps DB bulkhead/fencing |
| COMPARE | Five sequential independent cases; each defaults to 50 buyers, stock 10; NONE explicitly unsafe even if a sample does not oversell |
| COLD_WARM | Two sequential GETs, fixed buyers 2/concurrency 1; observation flag requires actual DATABASE then Redis source |
| STAMPEDE | Separate cold products, protection on then off; actual per-case SQL executions, not estimated misses |
| STALE_FILL | Fixed buyer/concurrency 1; demo-only pause after DB read (maximum 4 seconds), real PATCH commit/invalidation, release old reader, verification GET |
| OUTAGE | Fixed buyer/concurrency 1 per case; actual Redis probe, fallback GET, atomic purchase and Redis-assisted purchase, bounded wait for real recovery and same-key admission retry |
| LOST_RESPONSE | Fixed buyer/concurrency 1; deliberately discard one actual HTTP response, repeat its same scoped key, reconcile ledger; explicitly injected client loss, not a network fault |

For fixed guided scenarios, incompatible explicit buyer/concurrency settings
are rejected rather than silently ignored. Scenario observations can be false
(for example cache readiness changed or NONE did not oversell); results report
what happened rather than manufacturing the expected demonstration.

Both clients create runs and poll/export the backend. They contain no stock
accounting or alternative purchase implementation:

```powershell
.\scripts\flash-sale.ps1 -Compare -OutFile comparison.json
.\scripts\flash-sale.ps1 -Strategy PESSIMISTIC -Buyers 50 -Stock 10 -OutFile sale.json
.\scripts\fire-requests.ps1 -Json '{"scenario":"STAMPEDE","buyers":20,"concurrency":8}' -OutFile burst.json
.\scripts\fire-requests.ps1 -Json '{"scenario":"STALE_FILL"}' -OutFile stale.json
.\scripts\fire-requests.ps1 -Json '{"scenario":"LOST_RESPONSE","quantity":2}' -OutFile replay.json
# Return immediately with a run ID instead of polling:
.\scripts\fire-requests.ps1 -Json '{"scenario":"READ"}' -NoWait
```

```sh
# Bash clients additionally require curl and jq.
./scripts/flash-sale.sh COMPARE comparison.json
./scripts/fire-requests.sh --json '{"scenario":"COLD_WARM"}' --out reads.json
./scripts/fire-requests.sh --json '{"scenario":"READ"}' --no-wait
```

The scripts use `APP_PORT`/the safe `.env` allowlist, never an external host.
Ctrl+C or a polling failure attempts cancellation; it does not kill the app or
database. FAILED/INCONCLUSIVE/INTERRUPTED results are exported then reported as
client failures. A completed unsafe comparison may legitimately report FAIL.

For a real outage, use separate terminals from this checkout:

```powershell
docker compose --project-name flashsale-lab stop redis
.\scripts\fire-requests.ps1 -Json '{"scenario":"OUTAGE","durationSeconds":120}' -OutFile outage.json
# In another terminal, after WAITING_FOR_REDIS appears in cursor events:
docker compose --project-name flashsale-lab start redis
```

No HTTP endpoint executes these commands or controls Docker. Native users must
stop/start only their owned Redis process. Readiness requires listener health,
product-cache probes and a fresh epoch; admission has its own reconciliation.
If Redis was healthy at the start, the run explicitly says no outage was
demonstrated. If recovery does not occur before the deadline, the result is
INCONCLUSIVE. An open breaker is an observed unavailable call, not proof that
the Redis process is still physically down.

Runs, fixtures, attempts and keys are retained in the disposable lab database
for refresh/restart/export. Only events have automatic retention. Long-term
database housekeeping and statistically meaningful repeated benchmarks are
not implemented by this milestone.
