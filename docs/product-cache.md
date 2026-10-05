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
their exact observed bytes still match. Wrong Redis types are diagnosed without
unconditionally deleting an intervening writer's value.

An old fill cannot survive completed invalidation in the active namespace.
TTL starts at fill, not database commit; query duration and notification detection
lag prevent a strict commit-to-freshness guarantee of exactly 360 seconds.
LISTEN/NOTIFY loss and already-running reads remain explicit consistency limits.
