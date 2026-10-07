# HTTP API (milestone 6)

All routes are local. Start with `--spring.profiles.active=demo` (or benchmark)
to enable mutations. The default profile is read-only. `X-Client-Id` is a
controlled lab identity, **not authentication**. Do not publish these endpoints.

| Route | Contract |
|---|---|
| `GET /` | Locally bundled dashboard; no frontend runtime service/CDN |
| `GET /status` | Live database probe, milestone 6 capabilities, actual cache readiness/delays, active profiles and anonymous JVM/PostgreSQL runtime metadata |
| `GET /products/{id}` | 200/404 typed read envelope, with actual cache/DB source and publication outcome |
| `POST /products` | Full `{name,price,stock}`; 201, DTO and `Location` |
| `PATCH /products/{id}` | Explicit partial update; 200 DTO, 404 absent |
| `PUT /products/{id}` | **Nonstandard partial-update alias** for old workflows, identical to PATCH |
| `DELETE /products/{id}` | 204 if deleted, 404 absent |

IDs are positive signed 64-bit integers. Names are nonblank and at most 255
characters. Prices are JSON numbers from 0 through 9999999999.99, with at most
two decimal places. Stock is a JSON integer from 0 through 1000000. Partial
updates preserve absent fields, reject explicit nulls/unknown fields and reject
empty patches. Numeric strings and fractional stock are not coerced.

Request bodies are limited to 8192 bytes, including chunked bodies. Nonempty
bodies require `application/json`. State-changing browser requests require a
same-origin `Origin` and must not be cross-site. No permissive CORS is installed.
Use the literal loopback address or localhost, not arbitrary Host names.

Errors use `{code,message,requestId,retryable}`. The server generates a request
UUID and returns `X-Request-Id`; client-supplied tracing data is not blindly
logged. SQL text/stack traces never enter API error bodies. JPA optimistic
conflicts are 409. Database availability/lock/statement failures are 503.

```powershell
$base = "http://127.0.0.1:8080"
$p = Invoke-RestMethod "$base/products" -Method Post -ContentType "application/json" `
  -Body '{"name":"Laptop","price":1299.00,"stock":10}'
Invoke-RestMethod "$base/products/$($p.id)"
Invoke-RestMethod "$base/products/$($p.id)" -Method Patch -ContentType "application/json" `
  -Body '{"stock":7}'
```

There is **no automatic fixture seed**. Creates, edits and deletes survive
restart. Guided scenarios create explicit per-run isolated fixtures;
migrations never restore Laptop/Keyboard/Monitor over user edits.

## Product caching and diagnostics

Read envelopes contain `source`, `responseTimeMs`, `cacheWriteOutcome`, chronological `flow`
and product `data` (null for 404). Positive entries live 300-360 seconds; negative
entries 30 seconds. Hits do not extend expiry. `X-Cache` is `HIT` for
`REDIS_CACHE` / `REDIS_CACHE_AFTER_WAIT`, `MISS` for `DATABASE`, or `BYPASS`
for `DATABASE_FALLBACK`. A cache hit never means the purchase API consulted it
for stock decisions.

Publication reports `STORED`, `REJECTED_GENERATION`, `REJECTED_LOCK`, `FAILED`,
`SKIPPED_UNAVAILABLE`, or `NOT_ATTEMPTED` (hits and lease-waiter fallback).
`FAILED` can mean a write response was lost, not proof that Redis stored nothing.
Even a rejected stale fill can return the DB snapshot the request actually read.
That request is not retroactively linearizable with a concurrent writer.

`?stampedeProtection=false` is demo-only: it disables leases, not fencing or DB
capacity bounds. Default-profile use returns 403. Eight DB readers are permitted
by default; a saturated bulkhead returns 503 `DATABASE_OVERLOADED`. An interrupted
wait returns retryable 503 `INTERRUPTED`, never a fabricated cache hit.

| Demo-only route | Contract |
|---|---|
| `GET /cache/status` | Separate breakers, readiness, epoch, listener, recovery/error and read/limiter counters |
| `GET /cache/products/{id}` | Redis availability, typed value/presence and remaining TTL; no DB fill |
| `DELETE /cache/products/{id}` | Idempotent invalidation; absent is success; unconfirmed dependency result is 503 `CACHE_BYPASSED` |
| `DELETE /cache/products` | `INVALIDATED_NAMESPACE` and fresh epoch, not a physical deletion count |

Inspection distinguishes `PRESENT`, `CORRUPT`, `ABSENT_OR_EXPIRED` and `UNKNOWN`.
Redis cannot distinguish expired from never-created keys without extra retained
history; unavailable is never mislabeled absent. Readiness requires both healthy
LISTEN registration and Redis recovery, not simply a CLOSED breaker.

## Sliding-window request limit

All `/products` routes, including purchases and retries, share the controlled
client's default **10 accepted requests / 10 seconds** quota. Acceptance by the
limiter counts even if the subsequent API operation returns 404/409/503.
Missing `X-Client-Id` means `local`; invalid/oversized identities are rejected
before Redis. Request safety runs first. Status, cache diagnostics and demo
run/stock routes are excluded.

`X-RateLimit-Status` reports `ALLOWED`, `REJECTED`, `BYPASSED`, or explicitly
configured `DISABLED`. Healthy decisions include `X-RateLimit-Limit` and
`X-RateLimit-Remaining`. Rejection is 429 `RATE_LIMITED`, with `Retry-After`
rounded up to seconds, minimum 1; rejected requests do not extend the window.
After waiting, retry a purchase with its original idempotency key.

Redis errors/open limiter breaker **fail open for this lab**. `BYPASSED` omits
quota and Retry-After headers rather than inventing remaining capacity.
The DB bulkhead remains enforced. Rate-limit success cannot mark the product
cache READY. Client IDs are spoofable and there is no per-identity cardinality
defense for public deployment: this is not a trustworthy internet limiter.
See [product-cache.md](product-cache.md) for the Redis-time atomic boundary.

## Purchases and retries

`POST /products/{id}/purchase?strategy=ATOMIC_SQL` accepts `{"quantity":1}`.
The omitted body defaults to 1; null, missing quantity in an object, fractions,
numeric strings and quantities outside 1-1000 are invalid. ATOMIC_SQL is the
default. PESSIMISTIC holds a database row lock across check/work/decrement.
OPTIMISTIC reads stock/version, checks both in the UPDATE, and retries conflicts
in a fresh transaction (including a fresh idempotency claim), at most 20 attempts
with 1-5ms jitter. Exhaustion persists 409 GAVE_UP, never OUT_OF_STOCK.
Unknown strategies return 400 rather than silently substituting one.

`NONE` is an **intentionally unsafe**, demo-only unlocked check followed by an
unconditional decrement. Its response explicitly warns about overselling.
The first purchase attempt permanently classifies a product as UNSAFE or
PROTECTED, even if that attempt fails. Subsequent opposite-group attempts return
409 `FIXTURE_MODE_CONFLICT`; create separate products for comparisons. This
classification commits separately, before inventory work, without placing a
row lock across the unsafe check. All protected strategies may share a protected
product, but external SQL/admin edits are not covered by the conservation claim.
Negative protected fixtures return 409 `INVALID_FIXTURE`.

**Idempotency-Key is required:** 1-128 ASCII letters, digits, `.`, `_`, `:` or
`-`. Keys are scoped to `X-Client-Id` (1-64 safe ASCII characters, default
`local`), SHA-256 hashed in storage, never logged. The canonical fingerprint
includes product ID, quantity and resolved strategy. A key reused with a
different fingerprint returns **409 IDEMPOTENCY_CONFLICT**.

```powershell
$headers = @{ "X-Client-Id" = "buyer-1"; "Idempotency-Key" = "purchase-1" }
Invoke-RestMethod "$base/products/$($p.id)/purchase" -Method Post `
  -Headers $headers -ContentType "application/json" -Body '{"quantity":1}'
# Retry exactly the same request/key: no second decrement, replayed=true.
Invoke-RestMethod "$base/products/$($p.id)/purchase" -Method Post `
  -Headers $headers -ContentType "application/json" -Body '{"quantity":1}'
```

The unique database claim is acquired **before** simulated work. The conditional
stock decrement and terminal request/ledger result commit in one transaction.
The database's deferred constraint prevents accidentally committing an unfinished
claim. `purchase_ledger` is a view of committed SOLD request rows, not a second
independently written ledger. Product deletion never cascades into history.

200 SOLD, 409 OUT_OF_STOCK/GAVE_UP/ADMISSION_REJECTED and 404 NOT_FOUND are stable terminal outcomes.
An out-of-stock replay remains out-of-stock even after an administrative refill;
a new logical purchase needs a new key. A replay preserves the original
`purchaseId`, stock/version snapshot, attempts and `originalRequestId`; its
current `requestId` and observed `durationMs` describe this HTTP attempt.
`X-Purchase-Result` and `Idempotency-Replayed` identify the result.

Lock/claim waits are bounded to 2 seconds, individual statements to 5 seconds,
the transaction to 8 seconds and pool acquisition to 2 seconds. Retry a 503 or
lost response with **the same key**. An unknown commit result is not a proof of
rollback. Keys are retained for the life of the lab database in this milestone;
unbounded long-running public storage is not supported.

Stock-left is that purchase's committed snapshot, not necessarily stock now.
Safety assumes no concurrent unsafe/admin stock mutations. This is local
database deduplication, **not exactly-once execution or real payment integration**.

## Redis-assisted admission

`strategy=REDIS_ASSISTED` (legacy alias `redis`) requires an explicitly initialized,
trusted advisory counter. No automatic fallback or reset occurs during purchases.
The shared database claim is acquired before a unique reservation journal entry
is durably written and Redis Lua reserves the requested quantity. PostgreSQL still
uses the atomic conditional decrement. All other purchase response fields and
idempotency rules remain unchanged.

| Demo-only route | Contract |
|---|---|
| `GET /demo/stock/{id}` | Database stock/epoch, Redis availability/presence/stock/TTL separately, trust and unresolved journal count |
| `POST /demo/stock/{id}/reconcile` | Refuse an active fixture (409 FIXTURE_BUSY); resolve up to 100 pending reservations, then reset from locked DB stock under a fresh epoch |

```powershell
Invoke-RestMethod "$base/demo/stock/$($p.id)/reconcile" -Method Post
Invoke-RestMethod "$base/products/$($p.id)/purchase?strategy=redis" -Method Post `
  -Headers @{ "X-Client-Id" = "redis-buyer"; "Idempotency-Key" = "first" } `
  -ContentType "application/json" -Body '{"quantity":2}'
Invoke-RestMethod "$base/demo/stock/$($p.id)"
```

409 `ADMISSION_REJECTED` means Redis declined admission, **not** that DB stock
is zero. 503 `REDIS_UNAVAILABLE`, `ADMISSION_UNTRUSTED`, `ADMISSION_OVERLOADED`
and `RECONCILIATION_PENDING` are retryable dependency/readiness distinctions.
An expired/uninitialized counter requires drain/reconciliation. A stable
business rejection replays unchanged; use a new key for a new logical purchase.
Committed replays do not contact Redis to reserve again.

PATCH/PUT/DELETE and reconciliation reject in-flight local purchases. Locks
use 256 bounded stripes; rare unrelated stripe collisions conservatively
return FIXTURE_BUSY. This is single-instance coordination, not a distributed
run lock. DB triggers mark admission untrusted after admin/external updates or
non-Redis purchases; application restart also distrusts existing epochs.
Read [stock-admission.md](stock-admission.md) for TTLs, Lua states and crash limits.

## Persisted experiment API

All `/demo/runs` routes require demo or benchmark mode, including reads.
Mutations retain the normal same-origin/body/Host checks. Runs never accept a
target URL. Internal dispatch traverses the real product limiter with fresh
per-case/buyer identities and includes server-generated `requestId`/`runId`.

| Route | Contract |
|---|---|
| `POST /demo/runs` | Validated parameter object; 202 with run snapshot and Location; 409 RUN_BUSY if another run is active |
| `GET /demo/runs` | Latest 20 run identifiers/states |
| `GET /demo/runs/{id}` | Exact parameters, environment, fixtures, HTTP totals, live unquiesced inventory or durable final result |
| `POST /demo/runs/{id}/cancel` | Seal new dispatch and drain accepted work; terminal runs are unchanged |
| `POST /demo/runs/{id}/reconcile` | Retry failed finalization only after all workers/server requests stop; no new purchases; 409 RUN_NOT_QUIESCENT otherwise |
| `GET /demo/runs/{id}/events?after=0&limit=100` | Bounded cursor page, next/earliest cursor, exact dropped-event count, gap, truncation and hasMore |
| `GET /demo/runs/{id}/export` | Schema-versioned JSON with snapshot, all bounded attempts and first event page (paginate remaining events separately) |

`finalizationBlocked` means ownership was deliberately retained after drain or
storage failure. Restore the dependency and use the reconciliation route; never
reset stock to make a failed run appear successful. A process restart instead
marks unfinished work INTERRUPTED and key-fences its committed outcomes.

Defaults and eight scenario names are in [experiments.md](experiments.md).
`COMPLETED` means the harness finished, not that every buyer succeeded or that
NONE was safe. Read `invariantVerdict`, individual case verdicts, completion,
HTTP errors/rejections, transaction retries and scenario observations separately.
Unknown run IDs return 404 RUN_NOT_FOUND. Active fixture operations from outside
the runner and global cache-clear return 409 RUN_BUSY.
