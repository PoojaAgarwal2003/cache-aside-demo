# Milestone 1 evidence

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

**This author machine has no Docker engine or installed WSL distribution.**
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
