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
