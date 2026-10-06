# Persisted experiments

Milestone 4 is being delivered in three commits. The first two slices provide
bounded HTTP dispatch, durable evidence, inventory verification and restart
interruption. Guided scenarios and command-line clients follow in the last slice.

`POST /demo/runs` accepts a JSON object. Defaults are scenario `PURCHASE`,
strategy `ATOMIC_SQL`, 50 buyers, concurrency 10, stock 10, quantity 1, seed 1,
zero jitter, 60 seconds, and stampede protection enabled. `READ` uses the same
dispatcher. Unknown fields and user-supplied target hosts are rejected.

Bounds: buyers 1-100, concurrency 1-50 and no greater than buyers, quantity
1-1000, stock 0-1000000, jitter 0-100 milliseconds, duration 1-120 seconds.
Jitter is seeded; OS, transaction and network scheduling are not deterministic.
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
No cache-miss arithmetic or SQL-log scraping is used as a query counter.
