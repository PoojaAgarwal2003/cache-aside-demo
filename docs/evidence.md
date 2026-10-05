# Acceptance evidence

## Milestone 3: cache consistency and resilience

Recorded **2026-10-05** before the milestone-3 push, with the same Windows x64,
Temurin 17.0.20.1+1, PostgreSQL 16.15 and real Redis 7.4.11-in-WSL stack below.
The **Windows PowerShell 5.1** full `verify.ps1` entry point ran `check bootJar`.
**86 tests passed: zero failures, errors or skips.** Native execution is not
claimed as local Docker evidence.

| Suite | Tests | Evidence |
|---|---:|---|
| Unit suites | 10 | Existing input/identity bounds; limiter settings; Lua resource classloader ownership |
| Retained M1/M2 acceptance | 50 | Product/startup, all strategies, admission and actual child-process crash regressions |
| `ProductCacheClientAcceptanceTest` | 6 | Typed positive/negative TTLs, corruption, generation expiry, old epoch and compare-owner fencing |
| `CacheInvalidationAcceptanceTest` | 7 | HTTP caching, CRUD/SOLD invalidation, paused positive/negative fills, rollback, schema isolation and actual listener backend termination |
| `CacheResilienceAcceptanceTest` | 7 | Cold stampede on/off, bounded waiter, expired owner, database overload, real data-preserving Redis restart and lost fill response |
| `RateLimitAcceptanceTest` | 6 | Atomic concurrent quota, exact time boundary, natural expiry, identity validation, independent breaker and real-outage fail-open overload |

### Controlled observations

| Scenario | Observed assertion |
|---|---|
| Healthy cold and warm reads | DATABASE/STORED followed by REDIS_CACHE; missing product is cached as typed ABSENT and still returns 404 |
| Positive and absent TTL | Positive 300-360s at fill, absent 30s; cache hits never extend TTL; an overlong negative TTL is corrupt |
| Old positive/negative loader paused after DB read | Completed update/create invalidation makes old publication REJECTED_GENERATION |
| Actual listener backend terminated, external update during disconnection | BYPASS before recovery; reconnected listener rotates epoch; resumed old fill cannot populate the new namespace |
| Healthy shared cold load with leases | Controlled overlap shares the owner fill; protection-disabled case demonstrates four actual DB loads |
| Owner held beyond waiter deadline | Waiter performs DATABASE_FALLBACK/NOT_ATTEMPTED without a fill |
| Expired owner and successor lease | Old owner cannot publish or delete successor ownership |
| Eight DB loads held inside read bulkhead | Ninth read returns DATABASE_OVERLOADED; permits are restored after release |
| Redis stopped with explicit RDB save, DB changed, Redis restarted | Old cache bytes survive restart but the recovered epoch prevents their reuse |
| Redis fill completes, response loss injected immediately after Lua | FAILED does not pretend no write occurred; cache bypasses and recovers under a fresh epoch |
| 15 concurrent same-client HTTP requests within verified 10s | 10 accepted, five 429; another client proceeds; rejected purchase creates no sale |
| Exact lower/upper boundary | Production Lua with test-only clock-expression substitution removes lower-bound/future entries and retains `(now-window, now]` |
| Ten accepted limiter entries | State naturally expires, denied requests do not extend TTL, next request receives remaining quota 9 |
| Wrong-type limiter key opens only rate-limit breaker | BYPASSED without quota; product cache remains READY and other breakers CLOSED |
| Actual Redis process stopped with eight held fallback reads | Limiter explicitly fails open; ninth request remains 503 DATABASE_OVERLOADED |

No public fault/clock endpoint was added. Test-only probe implementations and
crash child classes are absent from the executable JAR. Existing inventory
concurrency fixtures explicitly disable the limiter; limiter tests explicitly
enable it so quota does not silently change stock experiments.

The full combined suite caught a lazy Lua resource owning the first request's
Tomcat classloader, which had already stopped during later app lifecycles.
Scripts now explicitly use the application classloader, with a focused regression.
Duplicate test-property overrides were made replace-not-append; native WSL
startup receives a bounded cold-boot deadline. None of these failures was marked
skipped or hidden with a success fallback.

### Packaged application

The **0.3.0 executable JAR** was launched and stopped through PowerShell 5.1
scripts on an isolated schema and owned Redis instance. It reported milestone 3,
READY, independent breaker status and the actual zero artificial delays.
Cold/warm/negative reads, committed PATCH invalidation, typed TTL inspection and
namespace rotation passed over HTTP. A measured **186ms** same-client sequence
produced **10 accepted / five 429**; this is a small correctness observation,
not a throughput benchmark.

Each of the five purchase strategies on its own stock-3 product committed a
quantity-2 sale and same-key replay. Both HTTP and SQL showed stock 1/version 1,
sold quantity 2, and no duplicate decrement. Owned runtime processes and the
isolated walkthrough schema are cleaned up; normal lab data is preserved.

### Container verification boundary

The fifth milestone-2 correction **passed real-container CI** in
[run 37337273418](https://github.com/PoojaAgarwal2003/cache-aside-demo/actions/runs/37337273418),
at `686cfc5da5065ab5fe8a04ce12662683f9554787`, tagged `milestone-2-corrected`.
The original `milestone-2` tag was not moved.

This record precedes milestone-3 publication; its authoritative full PostgreSQL/
Redis container result is the workflow for the `milestone-3` commit in
[GitHub Actions](https://github.com/PoojaAgarwal2003/cache-aside-demo/actions).
No local Docker/Compose execution, browser test, benchmark or screenshot is
claimed. Persisted runs, visual delivery and repeatability retain milestones 4-6.

---

## Milestone 2: inventory strategies

Recorded **2026-10-05**, before the milestone-2 push. Windows x64, Temurin
17.0.20.1+1, Gradle 9.8.0, Boot 4.1.1, native PostgreSQL 16.15 and **real Redis
7.4.11**, compiled from the checksum-verified official release inside an
isolated Alpine 3.22.6 WSL2 environment. Redis source SHA-256:
`3c266ece0abd54ed3b1c912c6eb86b7508cf382cb690ee6649d3843f018f6357`.
Tests start/stop their own foreground Redis instances; Redis was not mocked.
Read and purchase artificial delays were zero.

```powershell
.\scripts\verify.ps1 -NativePostgresPort 55439 `
  -NativeRedisWsl "FlashSaleLab-Test" `
  -NativeRedisBinary "/opt/flashsale/redis-7.4.11/src/redis-server"
```

**58 tests passed: 0 failures, 0 errors, 0 skipped.** Full `check bootJar`
passed through the **Windows PowerShell 5.1** verification entry point.

| Suite | Tests | Evidence |
|---|---:|---|
| Unit suites | 8 | Validation, identity/fingerprints, strategy and legacy alias canonicalization |
| `MilestoneOneAcceptanceTest` | 14 | Original HTTP/database/atomic/idempotency regressions retained |
| `StartupAcceptanceTest` | 1 | V1-to-V5 upgrade, restart persistence, default read-only and service-level NONE gate |
| `PurchaseStrategiesAcceptanceTest` | 16 | Healthy pessimistic 50/10, quantities/conservation, scoped retries, conflicts/exhaustion, isolated unsafe races |
| `RedisAdmissionAcceptanceTest` | 17 | Real Lua, admission/conservation, TTL/epochs, actual Redis stop/restart, unknown-commit fence, compensation and drain controls |
| `PurchaseCrashAcceptanceTest` | 2 | Parent forcibly terminates an owned Java process before/after commit, starts a fresh process and reconciles durable state |

### Controlled observations

| Scenario | Observed assertion |
|---|---|
| ATOMIC_SQL and PESSIMISTIC, 50 distinct buyers / 10 units | Each produces 10 unique unit sales, 40 OUT_OF_STOCK, final stock 0 |
| REDIS_ASSISTED, 50 distinct buyers / 10 units | 10 unique unit sales, remaining requests ADMISSION_REJECTED, stock/counter 0, no unresolved journal entries |
| Protected quantity-2 concurrency, starting stock 11 | Nonnegative stock; unique committed sold quantity + final stock = 11 |
| NONE, 2 buyers each requesting 2 from stock 2; barrier after both checks, repeated 3 times | Each run commits sold quantity 4, stock -2; ledger conservation still equals 2, clearly demonstrating that conservation alone does not establish safety |
| External SQL changes version after optimistic read | First attempt conflicts; second uses a different transaction ID and new version, then sells |
| Force a conflict on all 20 optimistic attempts | 20 distinct transaction IDs, GAVE_UP persisted/replayed, stock unchanged, no sale |
| 20 same-key requests using Redis legacy alias, quantity 2 from stock 10 | One reservation and purchase ID, stock/counter 8; fingerprint conflict rejects a changed quantity |
| Redis counter falsely reports zero while DB has 3 | ADMISSION_REJECTED, not OUT_OF_STOCK; stable replay after reset; new key can buy |
| Redis counter inflated to 99 while DB has 1, quantity 2 | PostgreSQL rejects with OUT_OF_STOCK; zero sold quantity; reservation compensated |
| DB trigger fails after decrement before completing SOLD | Transaction rolls back stock/claim, journal persists and compensation restores counter once |
| Reserve script repeated / release repeated | No second decrement / no second increment |
| Real counter expiry before compensation | EXPIRED recorded; counter remains absent, never resurrected |
| Counter replaced with another epoch before compensation | STALE_EPOCH recorded; replacement stock 99 is not inflated to 101 |
| Original scoped-key transaction fence still held, no committed row visible | Reconciliation returns bounded 503, leaves PENDING and does not refund; succeeds after fence release |
| Actual owned Redis process stopped after reservation | Rollback preserves DB stock; failed compensation remains PENDING; Redis inspection says UNAVAILABLE/UNKNOWN; atomic baseline still works; restart + reconciliation resolves the journal |
| API reset/PATCH/DELETE while purchase paused after reserve | FIXTURE_BUSY; reset succeeds only after the purchase drains |
| Force Java death after decrement/terminal row update but **before commit** | Restart sees stock 5, zero sold quantity, one PENDING journal; reconciliation releases, same-key retry makes one quantity-2 sale |
| Force Java death **after commit**, before cleanup/response | Restart sees stock 3, sold quantity 2, one PENDING journal; reconciliation marks COMMITTED without refund; same-key replay preserves purchase ID |

Crash tests run real child JVMs over HTTP with real PostgreSQL/Redis; only the
pause boundary is test-injected. Crash hook implementations are absent from the
executable JAR. Child logs, processes and isolated schemas are cleaned up.
An initial Windows log-handle cleanup race was fixed with bounded deletion
retry after process exit; the full suite subsequently passed.

The **packaged 0.2.0 app** was also launched and stopped through PowerShell 5.1
scripts. Each of the five strategies, on separate stock-3 products, committed
quantity 2, returned stock 1/version 1, and replayed the same purchase ID without
another decrement. Redis inspection showed counter 1 and no unresolved journal.
Startup scripts now use the stable `cache-aside-demo.jar` filename rather than
accidentally launching a previous milestone's versioned JAR. Bash syntax was
checked. No runtime screenshot or performance benchmark is implied.

### Container evidence and remaining boundaries

Local acceptance used native PostgreSQL plus Redis-in-WSL, **not Testcontainers**.
There is still no local Docker engine. The
[CI workflow](../.github/workflows/verify.yml) defaults to real digest-pinned
PostgreSQL **and Redis** containers, including the same crash suite; its release
result is visible in [GitHub Actions](https://github.com/PoojaAgarwal2003/cache-aside-demo/actions).
This record is written before push and does not invent a later CI result.
Milestone-1 Docker acceptance already passed in
[run 37196941718](https://github.com/PoojaAgarwal2003/cache-aside-demo/actions/runs/37196941718).

**Post-push CI finding:** the first milestone-2 container run
([37276867829](https://github.com/PoojaAgarwal2003/cache-aside-demo/actions/runs/37276867829))
failed the Redis restart test: Docker reassigned its random published port when
the fixture stopped/started the container, but the app retained the original
endpoint. The native backend had retained its port and did not expose this test
harness defect. The local correction keeps the container running and stops/starts
the actual Redis process inside it, retaining the endpoint. The outage test also
checks Redis `run_id` changes, proving a real process restart rather than merely
pausing traffic. The corrected sources compile, and all **19 targeted Redis
admission/process-crash tests pass** against native PostgreSQL plus real Redis in
WSL; this does not verify the changed Docker-only startup path.
The owner approved a fifth corrective commit on 2026-10-05. The original
`milestone-2` tag is retained; `milestone-2-corrected` identifies the correction.
**Subsequently verified:** its real-container CI passed in
[run 37337273418](https://github.com/PoojaAgarwal2003/cache-aside-demo/actions/runs/37337273418).

At the milestone-2 handoff, product caching, listener/recovery breakers, rate
limiting, persisted experiments, dashboard/browser tests, full Compose walkthrough
and benchmarks retained later gates. The cache/limiter slice is now covered in
the milestone-3 record above. These are small correctness checks, not capacity or SLA
measurements. The ledger/reservation journal survives process crashes; persisted
experiment-run reporting is not implemented until milestone 4.

---

# Milestone 1 evidence (historical)

Recorded **2026-10-04**, before the first milestone push. This is evidence for
the authoritative database slice only, not completion of the whole project.

## Environment and commands actually exercised

Windows x64; Temurin JDK 17.0.20.1+1; Gradle wrapper 9.8.0; Spring Boot 4.1.1;
JUnit 5.14.4; native PostgreSQL 16.15 on an isolated loopback port.
PowerShell scripts were exercised on **Windows PowerShell 5.1** as well as
PowerShell 7. Bash scripts passed syntax checks and the unit verification
entry point ran successfully through Git Bash.

```powershell
.\gradlew.bat test integrationTest  # With explicit native test URL selected
.\scripts\verify.ps1 -NativePostgresPort 55439
.\scripts\start.ps1 -SkipContainers -NoBuild -Profile demo
.\scripts\stop.ps1 -SkipContainers
```

Native tests use `flashsale_test`, fresh generated schemas and real HTTP to a
real Boot application. They do not mock PostgreSQL, Hibernate or transactions.
The application walkthrough separately used `flashsale_lab`. Demo read and
purchase delays were set to **zero** for acceptance and the recorded walkthrough.

## Automated results

**22 tests passed; 0 failures, 0 errors, 0 skipped.** `check` and `bootJar`
completed successfully through `verify.ps1`.

| Suite | Tests | Result |
|---|---:|---|
| `LabPropertiesTest` | 1 | Passed |
| `ProductInputTest` | 3 | Passed |
| `PurchaseRequestTest` | 3 | Passed |
| `MilestoneOneAcceptanceTest` | 14 | Passed against native PostgreSQL |
| `StartupAcceptanceTest` | 1 | Passed against native PostgreSQL |

Covered: clean migration, V1-to-V2 upgrade, three Boot lifecycles preserving
edits and purchase keys, default read-only mode, JPA/JDBC/direct-SQL version
agreement, real JPA optimistic conflict after external SQL, commit-only
notifications, rollback, partial PUT/PATCH, exact decimal validation, chunked
body bounds, same-origin rejection, quantities, key validation/conflicts,
terminal outcome replay, bounded lock contention and claim recovery.

## Controlled purchase observations

| Scenario | Actual result / database checks |
|---|---|
| 50 distinct buyers, 10 units, quantity 1, simultaneous start gate | 10 SOLD, 40 OUT_OF_STOCK; 10 unique purchase IDs; final stock 0; ledger sold quantity + final stock = 10 |
| 20 concurrent requests, one scoped key, quantity 2, starting stock 10 | 1 new purchase, 19 replays; one purchase ID; all stock snapshots 8/version 1; ledger quantity 2, final stock 8 |
| Discard original HTTP response then retry same key | Replayed original purchase; no duplicate decrement |
| Same key, changed quantity | 409 IDEMPOTENCY_CONFLICT; no hidden purchase |
| Same key, different client scope | Separate legitimate purchase |
| Previously out-of-stock key after refill | Replays OUT_OF_STOCK rather than inventing a new sale |
| Product row held by another transaction | Bounded 503; claim rolled back; same key succeeds after release |
| Database-injected failure after stock decrement, before request completion | 503 with sanitized body; stock unchanged; no ledger/claim; retry succeeds after removing the owned test trigger |
| Accidentally unfinished claim | PostgreSQL refuses commit via deferred constraint |

The gates create controlled overlap, not deterministic OS scheduling. These are
small correctness observations, **not throughput or latency benchmarks**.
No unimplemented strategy is included in these results.

## Packaged application and Windows operations

The built executable JAR was started with `start.ps1` on loopback. `/status`
reported an available database and explicitly unimplemented cache. An actual
quantity-2 purchase from stock 10 returned stock 8; replay returned the same
purchase ID. After stopping and starting a **fresh Java process**, the same
scoped key still returned that original purchase ID, stock 8 and version 1.

The final Windows PowerShell 5.1 lifecycle regression verified readiness,
identity-checked stop, launcher exit code **0**, released app port and removal
of the owned state file. The app is not left running and no load generator is
left active.

Observed integration issues were fixed, not hidden:

- The sync client locked Gradle's binary test output. `FLASHSALE_BUILD_DIR`
  permits non-synced build output while retaining the requested checkout location.
- Spring 7's JUnit extension requires JUnit 6. The suite retains JUnit 5 and
  explicitly manages real Boot application lifecycles instead.
- Testcontainers rejected a combined PostgreSQL `tag@digest` as a different
  repository. The acceptance fixture uses the same exact manifest via the
  supported digest-only reference.
- PowerShell 5.1 needed its HTTP assembly explicitly loaded for typed readiness
  exception handling.
- The two Windows scripts initially raced over a synced ownership file.
  State now lives in checkout-scoped local application data, with the launcher
  as the sole cleanup owner; the fresh lifecycle regression passed.

## Explicitly blocked / not claimed

**At milestone 1, this author machine had no Docker engine or installed WSL distribution.**
The default container-backed integration command was run without the native
override and failed with **"Could not find a valid Docker environment"**.
That failure was verified as a prerequisite failure, not marked skipped or passed.
After the probe, the complete native suite was rerun successfully, leaving
passing native reports.

The pinned [CI workflow](../.github/workflows/verify.yml) runs real PostgreSQL
Testcontainers acceptance on Ubuntu and checks Compose configuration.
Its authoritative execution status is available in
[GitHub Actions](https://github.com/PoojaAgarwal2003/cache-aside-demo/actions).
Local native results do not stand in for that container run.

No local Compose/Redis walkthrough, five-strategy comparison, cache outage,
process-crash injection, UI/browser test, screenshot or benchmark is claimed
in milestone 1. Those have explicit later delivery gates.

Generated XML/HTML test reports and runtime logs remain outside source control
under the configured build directory and `logs/`. Reproduce the checks rather
than treating this dated record as a claim about later modifications.
