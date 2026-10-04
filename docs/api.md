# HTTP API (milestone 1)

All routes are local. Start with `--spring.profiles.active=demo` (or benchmark)
to enable mutations. The default profile is read-only. `X-Client-Id` is a
controlled lab identity, **not authentication**. Do not publish these endpoints.

| Route | Contract |
|---|---|
| `GET /status` | Live database probe, actual capabilities, cache-not-implemented state and configured artificial delays |
| `GET /products/{id}` | 200/404 read envelope; milestone 1 always reads PostgreSQL, never claims a cache hit |
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
restart. Later guided scenarios will use explicit per-run isolated fixtures;
migrations never restore Laptop/Keyboard/Monitor over user edits.

## Atomic purchases and retries

`POST /products/{id}/purchase?strategy=ATOMIC_SQL` accepts `{"quantity":1}`.
The omitted body defaults to 1; null, missing quantity in an object, fractions,
numeric strings and quantities outside 1-1000 are invalid. ATOMIC_SQL is the
default and the only implemented strategy in milestone 1; other strategies
return 400 rather than silently substituting one.

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

200 SOLD, 409 OUT_OF_STOCK and 404 NOT_FOUND are stable terminal outcomes.
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
