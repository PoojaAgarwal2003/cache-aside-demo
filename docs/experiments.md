# Persisted experiments

Milestone 4 is being delivered in three commits. The first slice provides the
bounded HTTP dispatcher and durable request evidence; inventory verification,
restart interruption and guided scenarios follow in the remaining slices.

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

HTTP completion alone is not inventory correctness. The initial dispatcher
slice explicitly labels its inventory verdict `NOT_EVALUATED`; it does not
infer sales from HTTP counts.
