# Bruno: a real local API walkthrough

Open the `bruno` directory as a collection in Bruno and select **Local**.
Start the app with the demo profile and healthy dependencies first. Change
`baseUrl` to its explicit loopback port when needed; redirects and external
targets are refused. No account, cloud sync, secret, or developer sandbox is
needed. The collection uses Bruno's safe JavaScript sandbox.

Run all requests in sequence rather than starting with a replay or poll:

```powershell
# Once, for the exact pinned development tools:
npm.cmd ci
# Against your existing local demo app:
npm.cmd run test:api
# Optional non-default port:
npm.cmd run test:api -- --env-var baseUrl=http://127.0.0.1:8081
```

Bash equivalents use `npm` instead of `npm.cmd`.
For a completely owned app, isolated schema and actual PostgreSQL/Redis test
fixtures, `./gradlew browserTest` (`.\gradlew.bat browserTest` on Windows)
includes the native Bruno CLI collection as well as browser tests.

The 18 requests demonstrate status/readiness, an isolated new product, cold
then warm reads, partial PATCH and legacy partial PUT, quantity-two purchase,
same-key replay, changed-payload conflict, cache/breaker diagnostics, run start,
bounded final-result polling, authoritative export, cursor events, cancellation/
drain, and removal of the collection's own product. Persistent run records and
their fixtures remain available for inspection; no global reset is performed.

Fresh ordinary request identities avoid rate-limit contamination. The three
purchase calls deliberately share one fresh client and idempotency key:
the first commits two units, the second returns the same purchase/original
request IDs with stock still eight, and the third must fail with
`IDEMPOTENCY_CONFLICT`. Replay is not another sale. Raw keys are only local
request variables, not copied into the app's exported run evidence.

The warm-read request waits at most ten seconds for an actual cache source
(creation invalidation can race the first fill). A later assertion requires
that observed hit. Run/drain polling is capped at 45 seconds; each HTTP call is
capped at eight seconds. Failure is an assertion/prerequisite error, not a
skipped or fabricated result. The cancellation case uses 100 sequential reads
with bounded jitter; run it with the documented demo configuration.

For the six multi-request guided demonstrations, use the dashboard cards or
the shared shell runner described in [experiments](experiments.md).
Redis outage controls remain terminal-only:

```powershell
docker compose stop redis
# Start the dashboard OUTAGE guide; inspect fallback and admission.
docker compose start redis
```

Run this only from this project's directory/configuration. Never stop an
unrelated server. Redis persistence alone does not establish cache readiness.
