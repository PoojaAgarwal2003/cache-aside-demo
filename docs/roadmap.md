# Milestones and commit plan

Six milestones, **22 commits total** (4 + 5 + 4 + 3 + 3 + 3). Each commit is a
coherent implementation step, not a cosmetic split. Counts below include the
initial repository setup. Complete and verify one milestone, push its commits,
then **pause until the owner explicitly says to proceed**. Do not start the next
milestone automatically. Do not amend or force-push published history.

The source of requirements is [specification.txt](specification.txt).
Its five implementation phases are retained; visual delivery is split into
two milestones to keep the backend runner and dashboard independently reviewable.

| Milestone | Commits | Scope and delivery gate | State |
|---|---:|---|---|
| 1. Authoritative vertical slice | 4 | Reproducible boot, migrations, product API, atomic SQL purchase, persisted idempotency and real PostgreSQL evidence | Delivered; native and Docker CI verified |
| 2. Inventory strategies | 5 | NONE, pessimistic, optimistic and Redis-assisted; controlled races, compensation, conservation and process-crash evidence | Delivered; fifth correction passed Docker CI |
| 3. Cache consistency and resilience | 4 | Typed cache, generations/epochs, listener, leases, bulkhead, separate breakers/readiness, rate limiter and outage tests | Delivered; native and Docker CI verified |
| 4. Persisted experiment engine | 3 | Bounded HTTP-driven runs, fixtures, drain/cancel, reconciliation, durable events/results and shell entry points | Implemented; native 102-test gate and packaged clients verified; see tagged CI |
| 5. Visual lab and API exploration | 3 | Same-origin dashboard, five-way comparison, guided scenarios, Bruno, accessibility/browser coverage | Not started |
| 6. Repeatability and presentation | 3 | Benchmarks/raw evidence, whole-app restart/outage acceptance, real screenshots, polished docs/recording scripts | Not started |

**Current handoff:** milestone 4 contains three coherent commits, as authorized.
Pause before milestone 5. The fifth milestone-2 correction passed container
CI. Original `milestone-2` remains immutable; `milestone-2-corrected` identifies
that correction. [Evidence](evidence.md) separates native results and published
container verification. Milestone 4 delivers the [persisted runner](experiments.md)
and all six guided scenarios; browser presentation belongs to milestone 5.

## Milestone 1: authoritative vertical slice

1. `chore: bootstrap reproducible FlashSale Lab project`
   Pin supported Java-17-compatible dependencies, generate/verify Gradle wrapper,
   isolate Compose services, safe profiles and this delivery plan.
2. `feat: add versioned PostgreSQL product API`
   Flyway schema, version/notification triggers, JPA product CRUD, strict input
   and request/error boundaries. No fixture reseeding on restart.
3. `feat: commit atomic purchases with durable idempotency`
   Conditional SQL decrement plus scoped request claim and terminal result in
   one transaction. Replay/conflict handling and bounded database waits.
4. `test: verify first milestone and document local operations`
   Real database acceptance for trigger ownership, restart/upgrade, rollback,
   quantities, concurrent/lost-response retries and 50 buyers/10 units; operating
   scripts, evidence and explicit prerequisite limitations.

## Milestone 2: inventory strategies

1. Shared strategy boundary; pessimistic/optimistic implementations and fresh
   bounded attempts; quantity, conflict and exhaustion tests.
2. Demo-gated NONE plus barrier-controlled race; isolated fixtures and ledger
   conservation assertions. Unsafe operations never mix with protected runs.
3. Redis admission with counter epochs, unique idempotent reservations, bounded
   compensation and honest unavailable/rejection outcomes.
4. Crash/unknown-commit reconciliation, expired-counter regressions and
   comparisons with the atomic baseline. Include healthy pessimistic 50/10.
5. Preserve the Docker endpoint across real Redis process restarts; verify a
   changed Redis process run ID. Owner-approved corrective commit, no rewrite.

## Milestone 3: cache consistency and resilience

1. Typed positive/negative entries, fixed jittered TTLs, corrupt-state diagnostics,
   random generations and atomic fenced fill/invalidation.
2. Owned LISTEN connection, post-commit invalidation, epoch rotation and
   controlled positive/negative stale-fill/listener reconnect tests.
3. Compare-token rebuild leases, bounded wait/fallback bulkhead, independent
   circuit breakers and serialized readiness recovery.
4. Redis-time sliding-window limiter, explicit fail-open metadata and actual
   Redis outage/data-preserving restart tests.

## Milestone 4: persisted experiment engine

1. Per-run fixtures, scoped client identities, bounded real-HTTP dispatcher and
   seed/parameter/run metadata.
2. Cursor events, persistence, restart interruption, drain/cancel and final
   ledger-based invariant reconciliation; never turn unknown into PASS.
3. Read/purchase/guided-scenario APIs and PowerShell/Bash wrappers over the same
   runner; metrics attribution and export tests.

## Milestone 5: visual lab and API exploration

1. Responsive local-asset dashboard with actual persisted snapshots, large
   counters, request details and explicit errors/loading/cancellation.
2. Strategy comparison, six guided scenarios, showcase view and Bruno collection
   with environment/assertions/walkthrough.
3. Browser coverage against the real app: refresh/export, keyboard/reduced
   motion/mobile, interruptions/event gaps and dependency failures.

## Milestone 6: repeatability and presentation

1. Benchmark profile, controlled warmups/trials, sample-aware percentiles,
   measured raw exports and hardware/runtime/delay disclosure.
2. Complete Compose walkthrough: five strategies, Redis recovery, lost response,
   actual process interruption, persisted exports and all acceptance suites.
3. Capture actual screenshots and recording examples, finish diagrams/settings/
   feature maps/limitations, record final evidence and known gaps.

## Acceptance discipline

Every release note separates implemented features, measured results and
unverified prerequisites. No skipped Testcontainers run counts as passed.
Retain the supplied specification's single-instance, eventual-cache, advisory-
Redis and no-exactly-once limitations throughout all milestones. No real
payments, public hosting, arbitrary external load targets or unrelated services.
