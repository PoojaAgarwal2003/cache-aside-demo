# Complete process/Compose walkthrough

This development gate runs the **real packaged JAR**, digest-pinned PostgreSQL
and Redis services from the repository's Compose file. It is not a mock or a
Testcontainers substitute. Requires JDK 17, Docker Linux containers/Compose v2
and Node 22+ (no npm dependencies). The host app still binds only to loopback.

```powershell
.\scripts\walkthrough.ps1 -OutDirectory walkthrough-results\local
```

```sh
./scripts/walkthrough.sh --out walkthrough-results/local
```

Output must be a **new** directory. A unique `flashsale-walkthrough-<id>` Compose
project owns three dynamically selected loopback ports, isolated named volumes,
and one child JVM. It does not attach to or interrupt your normal `flashsale-lab`
project. Transient port discovery has a normal bind race: occupied ports cause
startup failure, never termination of an unrelated process. `.env` port choices
are intentionally not reused by this isolated gate.

The same scenario driver is exercised by `tests/browser/lifecycle.spec.mjs`
against the owned test host; native results are labeled separately. The Compose
path additionally checks committed stock/ledger directly with `psql`, restarts
PostgreSQL itself, and runs 2 warmups + 5 zero-delay comparison trials.

| Stage | Required observation |
|---|---|
| Five strategies, 50 buyers/10 units | Protected conservation/nonnegative inventory; healthy atomic/pessimistic sell exactly 10; NONE always unsafe, overselling not fabricated |
| Cold/warm, stampede, stale fill | Actual cache sources/SQL counts; old generation rejected after committed writer; stampede counts retained, not forced to a guessed HIT/WAIT ratio |
| Lost-response replay, quantity 2 | Explicitly injected client discard after actual HTTP receipt; one unique committed sale, two units, one replay |
| Actual Redis stop/start | DATABASE_FALLBACK, limiter BYPASSED, atomic SOLD, admission REDIS_UNAVAILABLE; READY recovery, changed Redis process run ID, retained marker |
| Kill app with accepted work | INTERRUPTED/INCONCLUSIVE after restart; no automatic redispatch; unknown pre-crash durations remain null |
| Second whole-app restart | Completed and interrupted exports remain byte-value-equivalent JSON objects |
| Direct SQL and PostgreSQL restart | Database stock/ledger agree with comparison; persisted export survives DB and app restart |
| Benchmark profile | Actual zero-delay trial exports and sample-aware summaries, never mixed with demo delays |

`lifecycle.json`, seven named raw run exports, `direct-ledger.json`,
`compose.json`, app output and `benchmark/` retain the actual evidence.
`compose.json` records project/ports/image metadata, dependency persistence
checks and cleanup. Ctrl+C requests bounded run cancellation, then stops only
the owned JVM and project services. A failed dependency/cleanup is an explicit
nonzero exit, not a successful report. Inspect retained errors before retrying.

**No named volume is deleted.** Successful cleanup verifies no services remain
running in that exact generated project. Stopped containers and named volumes
are intentionally retained; record the printed project name if you later choose
to inspect or remove your disposable evidence environment. Normal application
start/stop and this gate never run `down -v`.

CI runs this independently of Java acceptance and browser gates, retaining
raw artifacts for 30 days. Native lifecycle tests cannot establish that this
Compose job passed; check the exact published commit's workflow.
