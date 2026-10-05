# Product cache contract

PostgreSQL remains authoritative. Only product reads populate Redis, using JSON
DTO envelopes (`schemaVersion=1`, `PRESENT`/`ABSENT`). This is eventual caching,
not a linearizable inventory decision or durable change feed.

Data, generation and lease keys are isolated by schema, a random process/cache
epoch and product ID. A missing generation uses a fresh UUID and `SET NX`, never
a reusable sentinel. Invalidation changes generation and deletes data atomically.
Publication compares the captured generation and optional owner token in Lua.
An expired/missing generation rejects an old fill; compare-token release cannot
delete a successor's lease.

Positive expiry is fixed at 300 seconds plus 0-60 seconds jitter; absent entries
expire after 30 seconds. Hits never slide expiry. Generation keys expire after
600 seconds and are extended only by successful publication or invalidation,
longer than data TTL plus the 8-second maximum publication age. Leases last five
seconds. Inactive and retired namespaces expire naturally; no `KEYS`/`FLUSHDB`.

Lookup distinguishes HIT, HIT_ABSENT, MISS, UNAVAILABLE and CORRUPT. Inspection
separates availability, presence, decoded value, remaining TTL and diagnostic.
Redis cannot distinguish a never-created key from one already expired without
extra retained history, so both are honestly `ABSENT_OR_EXPIRED`; outage is
`UNKNOWN`, not missing. Malformed/schema-invalid strings are removed only if
their exact observed bytes still match. Wrong Redis types are diagnosed and
removed within the atomic type check, never deleting an intervening valid writer.

An old fill cannot survive completed invalidation in the active namespace.
TTL starts at fill, not database commit; query duration and notification detection
lag prevent a strict commit-to-freshness guarantee of exactly 360 seconds.
LISTEN/NOTIFY loss and already-running reads remain explicit consistency limits.

The listener owns a separate JDBC connection, not a permanently borrowed Hikari
slot. It registers `LISTEN`, uses bounded notification/heartbeat waits and
250ms-to-3s reconnect backoff, and closes its own connection at shutdown.
Notifications include their schema; other isolated lab schemas cannot invalidate
this instance's fixtures. Notification handlers only invalidate, never load data.

API writes and SOLD purchases register the same invalidation helper within their
database transaction. It runs after successful commit, not before commit or on
rollback. Redis failure marks cache BYPASS without changing a committed result.
Unknown transaction completion also requires recovery. On startup or listener
reconnect the coordinator requires LISTEN plus Redis health, rotates to a new
UUID namespace and publishes READY. A fill paused in an older epoch is rejected.

Demo-only inspection: `GET /cache/status`, `GET /cache/products/{id}`.
`DELETE /cache/products/{id}` is idempotent invalidation when Redis is ready;
an unconfirmed invalidation returns an explicit 503 with cache bypassed.
`DELETE /cache/products` rotates namespace, never claims a physical deletion count.

## Stampede, overload and dependency recovery

Healthy misses use a five-second random-owner lease, then **recheck** cache before
querying. Other readers poll every 50ms for at most three seconds, then perform an
uncached fallback (or receive explicit overload); waiters never publish. Every
database product load uses an eight-permit bulkhead with a 250ms acquisition
deadline. HTTP threads/connections/accept queues are also bounded. A slow owner
can outlive its lease and overlap another query; generation and owner checks
reject its publication rather than claiming one query under arbitrary failures.
Demo-only `?stampedeProtection=false` omits leases, not generation fencing.

Named Resilience4j breakers `product-cache`, `rate-limit`, `stock-admission` have
independent ten-call windows, minimum five calls, 50% failure threshold, ten-second
open wait and three half-open trials. Transport/connect timeout is 500ms. Only
health probes use product-cache HALF_OPEN; the listener's serialized coordinator
waits for CLOSED, confirms LISTEN health, then rotates epoch before enabling reads.
CLOSED alone is not cache readiness; successful rate/admission operations cannot
publish READY. No breaker transition callback flushes or recursively calls Redis.

`GET /cache/status` reports breaker state/counters separately from readiness,
listener health, last recovery/error, epoch and read/load/wait/overload counts.
Recovery tests stop and restart the actual owned Redis **preserving its RDB**;
old data remains in Redis but cannot be read from a newly trusted epoch.

## Sliding-window limiter

`RateLimitFilter` runs after the loopback/origin/body/identity boundary, using the
servlet's decoded path so equivalent product URLs share the limit. It excludes
status/debug/demo routes. A schema/client sorted-set key stores only accepted
requests, with UUID members. One Lua invocation uses Redis `TIME` in milliseconds,
removes scores at or below `now-window` and above `now`, counts, then either rejects
or adds the new request and sets fixed window expiry. Thus retained timestamps
are exactly in `(now-window, now]`; rejection does not add or extend state.
Retry delay comes from the oldest remaining accepted timestamp.

Defaults: enabled, 10 accepts, 10,000ms. `lab.rate-limit.limit` is bounded 1-1000;
`window-ms` 1000-60000. Disabled is explicit in headers/status, never disguised as
healthy enforcement. The separate `rate-limit` breaker fails open with BYPASSED,
no invented quota. Accepted HTTP requests can still fail business validation or
be rejected by the DB bulkhead. Identity is controlled lab input, not auth.

Cache/read and limiter settings are listed in [operations.md](operations.md).
Real Redis acceptance covers 15 concurrent HTTP requests within a measured
window, exact boundaries via a test-only clock-expression substitution of the
production script, natural expiry, distinct clients, independent breaker failures
and fail-open overload. Production has no clock/fault override endpoint.
