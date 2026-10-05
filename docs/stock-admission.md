# Advisory stock admission

Redis is an optional admission gate, never an inventory authority. A high or
drifting counter cannot bypass PostgreSQL's `stock >= quantity` predicate.
A low counter can falsely reject purchases while inventory remains. No automatic
fallback, per-purchase resync, payment, or exactly-once delivery is implied.
Use separate products for unsafe NONE and protected comparisons.

## Transaction and recovery boundary

1. Take the local fixture read guard and one of four admission worker permits
   (2-second bounded wait). Register fixture mode before inventory work.
2. In a fresh PostgreSQL transaction, take a scoped-key advisory transaction lock
   and acquire the unique idempotency claim. A duplicate replays before reserving.
3. Read the trusted DB epoch. In a separate short transaction, commit a PENDING
   journal row containing a random reservation UUID, epoch, quantity and hashed
   scoped key. No raw key is stored. Four worker permits bound nested connections.
4. Lua reserves against that epoch. Record the reservation UUID on the owning
   claim. Simulate work, conditionally decrement PostgreSQL stock and complete
   the terminal purchase row in the same inventory transaction.
5. After that transaction ends, resolve the journal in a fresh bounded transaction.
   The resolver first takes the **same scoped-key advisory lock**, then reads the
   committed request row. Absence alone without this fence is not proof of rollback.
   Only SOLD with this exact reservation UUID means COMMITTED. Otherwise release
   with an epoch-checked idempotent Lua operation.

PostgreSQL unavailability or an ambiguous lock/commit result leaves PENDING; it
never triggers a blind refund. Failed compensation logs a warning and is visible
in `unresolvedReservations`. Cleanup does not turn a committed SOLD into an error.
Automatic cleanup has a 3-second loop deadline (plus the last bounded resolver
transaction); unresolved work is logged and left for explicit reconciliation.
Retries use the same key; a later purchase attempt cannot double-decrement.
The journal survives crashes before/after Lua and inventory commit. Resolvers
also serialize their own journal-row changes, so release-before-journal-commit
crashes can repeat safely.

## Redis keys and exact return states

Keys are scoped to the configured lab schema and product:
`flashsale:{schema:productId}:stock` and
`flashsale:{schema:productId}:reservation:random-uuid`.
Counter hashes contain a random epoch UUID and integer stock. **Counter TTL:
60,000ms**, fixed from reset, never extended by reserve/release. **Reservation
metadata TTL: 120,000ms**, fixed from reserve, not extended by repeated calls.
It outlives the original counter; no key is immortal. Database journal/idempotency
history is retained for the life of this educational database.

| Script | Return states |
|---|---|
| Reserve | RESERVED (first decrement), HELD (same reservation, no decrement), REJECTED (advisory insufficient stock), MISSING_COUNTER, STALE_EPOCH, CORRUPT, ALREADY_FINISHED |
| Release | RELEASED (first increment or already released, no second increment), NOT_RESERVED (no metadata), EXPIRED (counter absent), STALE_EPOCH (replacement counter), CORRUPT |
| Reset | RESET (replace only this product counter with a new epoch and fixed TTL) |
| Inspect | ABSENT, CORRUPT, or atomic epoch/stock/TTL snapshot; transport failures remain UNAVAILABLE/UNKNOWN |

Release never creates a counter or increments a replacement epoch. Missing
metadata is not proof that an old reserve never happened: expiry can erase it.
Reconciliation resolves the old journal, then rebuilds from DB only after drain,
so missed compensation becomes explicit drift, not an invented sale or refund.
Unexpected script states keep the request/journal unresolved rather than
silently treating Redis errors as stock exhaustion.

## Controlled reset and invalidation

`POST /demo/stock/{id}/reconcile` takes the exclusive local fixture guard, rejects
active work, resolves at most 100 PENDING rows with a 15-second loop deadline, and
locks the DB product before reading stock. Each resolver transaction has a
2-second lock/3-second statement/5-second transaction deadline. A final in-flight
operation can finish after the loop deadline; this is not a hard 15-second SLA.
Remaining unresolved work returns 503; repeat after dependencies recover.
Only after resolution does reset publish a random epoch in Redis and PostgreSQL.
An unknown reset commit is safe: admission requires the Redis and committed DB
epoch to agree. Negative inventory cannot initialize a protected counter.

Application restart distrusts all existing DB admission epochs. A product UPDATE
outside a Redis-admitted purchase marks that product's epoch untrusted inside the
same DB transaction, including direct SQL, API edits and atomic/pessimistic/
optimistic purchases. The transaction-local marker used by the trigger is an
implementation convention, not an authentication mechanism. API admin mutation
guards extend through transaction commit; external SQL is not covered by the
application-local guard. Concurrent outside writes remain outside run guarantees.

Crashes, missed compensation, external edits and key expiry can all cause drift
or false rejection. Inspect shows database and counter values separately; it is
not a transactionally consistent global snapshot. No automatic background scan,
KEYS, FLUSHDB, blind INCRBY, or refill on each purchase is used. Milestone 3 adds
an independent `stock-admission` breaker through the shared Redis gateway;
its recovery never implies product-cache readiness. Product-read caching,
listener/epoch recovery and limiter policy are separate contracts documented in
[product-cache.md](product-cache.md), not stock-authority guarantees.
