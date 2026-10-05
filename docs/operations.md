# Local operations

## Windows (PowerShell 5.1 or 7)

Install a JDK 17 and Docker Desktop (Linux containers, Compose v2). Set
`JAVA_HOME`. The scripts do not install software, require admin rights, use Bash
or stop unrelated processes. Default application/database/Redis ports are
8080/55432/56379, **all loopback-only**.

```powershell
Copy-Item .env.example .env
.\scripts\start.ps1
# In a second terminal:
Invoke-RestMethod http://127.0.0.1:8080/status
.\scripts\stop.ps1
```

`start.ps1` checks the app port, waits for Compose health, builds the executable
JAR, records PID + process start time + executable identity, and waits for an
actual `/status` database probe. It remains attached to the terminal. Use
Ctrl+C or `stop.ps1`, which refuses to kill a recycled/mismatched PID.
`stop.ps1` preserves both named database volumes. No normal script deletes them.
Ownership state is kept under the user's local application-data directory,
namespaced by a hash of this checkout's path, **not in a synced checkout**.
The foreground launcher owns final state-file cleanup; the stop command does
not race it by deleting the same live ownership file.

`start.ps1 -Profile default` disables all mutations and artificial latency.
`start.ps1 -Profile benchmark` enables lab mutation but sets both delays to zero.
Neither constitutes a benchmark experiment harness yet.

Scripts load only the documented `.env` keys; existing process environment wins.
Direct `gradlew bootRun` does **not** read `.env`.

```powershell
# A checkout in a synced folder can use a separate, non-synced output directory:
$env:FLASHSALE_BUILD_DIR = "$env:LOCALAPPDATA\FlashSaleLab\build"
.\scripts\verify.ps1 -UnitOnly
.\scripts\verify.ps1                # Requires real Docker; no skipped pass
```

The wrapper uses the exact pinned Gradle distribution and checksum; no global
Gradle install is needed. Full verification always executes the integration
task rather than reusing a previous database environment's cached result.

## Explicit native PostgreSQL and WSL Redis for developers

This is an **alternative real-database test backend**, not a silent Docker
fallback. Install PostgreSQL 16.15 from the
[official Windows binaries linked by EDB](https://www.enterprisedb.com/download-postgresql-binaries).
Run only a project-owned server bound to `127.0.0.1`; create dedicated databases
`flashsale_lab` and `flashsale_test`, with a local `flashsale` role. Do not point
these tests at a valuable database. Elevated Windows terminals must use
PostgreSQL's `pg_ctl` restricted-user launcher, not direct elevated `postgres`.

```powershell
# Example: project-owned PostgreSQL already listening on 55439, with an
# existing dedicated WSL distribution containing real Redis 7.4.11.
$env:FLASHSALE_TEST_DB_USER = "flashsale"
$env:FLASHSALE_TEST_DB_PASSWORD = "local-lab-only"
.\scripts\verify.ps1 -NativePostgresPort 55439 `
  -NativeRedisWsl "FlashSaleLab-Test" `
  -NativeRedisBinary "/opt/flashsale/redis-7.4.11/src/redis-server"

$env:POSTGRES_PORT = "55439"
$env:APP_PORT = "58080"
.\scripts\start.ps1 -SkipContainers
.\scripts\stop.ps1 -SkipContainers
```

Acceptance generates an isolated schema per fixture and drops **only that
generated schema** after closing the application. It does not erase the lab
schema or delete volumes. `-SkipContainers` does not start or stop a native
PostgreSQL server; its owner must manage its lifecycle explicitly.
The WSL Redis override launches one **owned foreground Redis process** per
fixture on a fresh loopback port, verifies readiness, and shuts down only that
instance. Its matching `redis-cli` must be beside `redis-server`. Test Redis
disables persistence and never uses the application's data volume.
Both WSL flags are required together; omitting them uses Testcontainers Redis.
The scripts do not install WSL, Redis or PostgreSQL. A normal native app launch
still needs its own Redis on `REDIS_PORT` for REDIS_ASSISTED; these short-lived
test Redis instances are not application dependencies.

For direct Gradle debugging, `FLASHSALE_TEST_JDBC_URL` may contain only
`jdbc:postgresql://127.0.0.1:<port>/flashsale_test` (no arbitrary hosts/options).
`verify.ps1` requires its explicit native-port argument and otherwise clears
that selection for the run. Native tests never masquerade as Testcontainers.
Direct Gradle native Redis selection uses `FLASHSALE_TEST_REDIS_WSL` and
`FLASHSALE_TEST_REDIS_BINARY`. The default verification scripts clear inherited
test backend selections unless explicitly supplied. The default Linux/macOS
verification uses real Testcontainers for both databases.

## Strategy comparison without the later experiment runner

Create a separate product for each strategy using [the API walkthrough](api.md).
Never reuse a NONE product for protected purchases. For REDIS_ASSISTED, call
`POST /demo/stock/{id}/reconcile` **before** buying; use
`GET /demo/stock/{id}` to compare the advisory counter with authoritative stock.
Counter expiry after 60 seconds requires explicit drain/reconciliation, not
an automatic refill that could restore quantities still reserved by buyers.
API resets/deletes are rejected while that fixture has active local work.

Run `integrationTest` for the controlled concurrency comparison and actual
before/after-commit child-process termination tests. They traverse real HTTP,
then check committed ledger quantities and DB stock, not just HTTP 200 counts.
The runnable user-facing experiment harness and cancellation belong to milestone 4.

## Linux/macOS

```sh
cp .env.example .env
./scripts/start.sh demo
# Ctrl+C stops the foreground Java process.
./scripts/stop.sh
./scripts/verify.sh --unit-only
./scripts/verify.sh
```

The Bash app runs as the foreground process, not an unidentified detached
daemon. Bash `stop.sh` stops only the Compose services; use Ctrl+C for that
terminal's app. Shell syntax and the container-backed verification command
are checked in CI. Windows execution does not depend on these files.

## Settings and bounds

| Setting | Default | Bound / meaning |
|---|---|---|
| `APP_PORT` / `POSTGRES_PORT` / `REDIS_PORT` | 8080 / 55432 / 56379 | Script ports 1024-65535, loopback only |
| `DB_PASSWORD` | `local-lab-only` | Disposable lab value, configure locally |
| `READ_DELAY_MS` (demo) | 1000 ms | 0-2000; synthetic, not measured DB latency |
| `PURCHASE_DELAY_MS` (demo) | 100 ms | 0-1000; before inventory decision |
| Hikari maximum / minimum | 16 / 2 | Bounded application connections |
| Pool acquisition | 2000 ms | Explicit availability error on exhaustion |
| Purchase lock / statement / transaction | 2 / 5 / 8 seconds | Bounded waits; retry purchases with same key |
| Optimistic attempts / retry jitter | 20 / 1-5 ms | Fresh transactions; exhaustion is GAVE_UP, not OUT_OF_STOCK |
| Redis command / connect timeout | 500 / 500 ms | Admission fails explicitly; no strategy fallback |
| Redis admission workers / permit wait | 4 / 2 seconds | Bounds nested journal/inventory connections |
| Counter / reservation TTL | 60 / 120 seconds | Fixed expiry; release never recreates an expired counter |
| Automatic cleanup loop / manual reconciliation loop | 3 / 15 seconds | Monotonic loop bounds plus the final bounded resolver transaction |
| HTTP threads / connections / accept queue | 64 / 128 / 64 | Local server backpressure, not a public SLA |
| JSON body / headers | 8192 bytes / 8 KB | Chunked bodies also bounded |
| Product initial/reset stock | 0-1000000 | No negative API reset |
| Purchase quantity | 1-1000 | No coercion; omitted body alone defaults to 1 |
| Client ID / idempotency key | 64 / 128 chars | Safe ASCII, scoped identity, no authentication |
| Application / cache-flow logs | 50 / 25 MB rotation caps | 7-day history; current active files are additional |

Current runtime logs are `logs/app.log` and `logs/cache-flow.log`; the latter has
no fake product-cache events in milestone 2. Both are ignored by Git. Test reports are
under the chosen build directory's `reports/tests` and `test-results`.

For raw database verification:

```sql
SELECT id, stock, version, updated_at FROM lab.products ORDER BY id;
SELECT product_id, sum(quantity) AS sold FROM lab.purchase_ledger GROUP BY product_id;
SELECT product_id, epoch, trusted FROM lab.stock_admission_epochs;
SELECT product_id, state, count(*) FROM lab.stock_reservations GROUP BY product_id, state;
-- An ordinary external write advances the version trigger:
UPDATE lab.products SET name = 'Edited externally' WHERE id = <your_fixture_id>;
```

No script accepts an arbitrary external load destination, disables TLS
verification, deletes existing volumes or stops services by process name.
