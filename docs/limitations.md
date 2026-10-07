# Scope and correctness limits

- This is a **single application-instance local educational lab**, not a
  production inventory service or distributed-system certification.
- Milestone 6 implements all five strategies, advisory Redis admission, typed
  product caching, listener recovery, bounded rebuilds, separate breakers and a
  lab limiter, a persisted HTTP experiment runner and the local dashboard.
  Repeated benchmarks and presentation use actual retained evidence, not
  scalability or statistical-certification claims.
- No real payment is performed. Atomic stock/ledger commit and durable key
  deduplication do not imply exactly-once request execution or payment delivery.
- `X-Client-Id` is a spoofable lab identity, not authentication. Keys have meaning
  only within that claimed identity. Loopback binding and same-origin checks
  are local safety boundaries, not a public deployment security design.
- Stock conservation for protected purchases assumes an isolated fixture with
  **no concurrent unsafe or administrative stock changes**. Ordinary product
  PATCH can intentionally reset stock after drain. Local fixture guards reject
  API resets during in-flight purchases; direct SQL is outside those guards.
  The runner also refuses outside API changes across a whole active run.
- The product schema deliberately omits `CHECK (stock >= 0)` so the
  demo-only NONE race can show negative inventory. A real service would normally
  enforce that CHECK as an additional defense. API starting/reset stock cannot
  be negative; protected strategies reject negative starting fixtures. NONE
  and protected purchases cannot share a product's permanent purchase mode.
- Same-key replay returns the original terminal decision, not current stock.
  Keys are retained indefinitely within this disposable lab; request volume and
  ledger disk retention are not a public service offering.
- Timeout/connection failure near commit means **unknown**, not necessarily
  rollback. Reuse the key. Do not automatically refund or submit a new key.
- Pool and database waits are bounded. Safety does not promise every buyer
  succeeds under failures. The measured small healthy 50/10 case is not an SLA.
- Redis counters can drift or falsely reject while DB stock remains. Epochs and
  idempotent compensation prevent blind inflation, not all advisory drift.
  Only explicit drained reconciliation resets from DB stock; pending journal
  records remain visible if database/Redis resolution fails. Product edits,
  non-Redis purchases and app restart distrust the epoch. See
  [stock-admission.md](stock-admission.md).
- The demo profile uses explicit synthetic read/purchase delays (1000/100 ms by
  default). These are not real PostgreSQL latency measurements.
- The owned LISTEN connection invalidates only after committed notifications;
  it is not durable CDC. Cached reads are eventual, not linearizable. Already
  running requests can return old snapshots; fencing prevents an old fill from
  surviving completed invalidation. TTL begins at fill, not at commit, and is
  not an exact commit-to-freshness deadline.
- Known listener/Redis failures bypass caching until coordinated health checks
  rotate epoch. There is a detection interval before a failure is known.
  Successful limiter/admission calls and CLOSED breakers alone cannot establish
  cache freshness. Recovery does not physically delete old namespaces.
- Redis leases limit healthy cold-load duplication, not arbitrary-failure
  exactly-one execution. Slow owners can overlap successors; expired ownership
  rejects publication. Waiters can use uncached fallback, with bounded capacity.
- The limiter uses Redis time, so clock adjustments affect window semantics.
  Entries outside `(now-window, now]` are removed atomically. Spoofable identities,
  deliberate disable and outage fail-open make it a lab demonstration, not
  public abuse protection. Redis quota state is not durably replicated; a
  restart can reset quota. DB backpressure still applies.
- Redis cannot distinguish never-created from already-expired data without
  retained history; inspection reports `ABSENT_OR_EXPIRED`, not a fabricated
  expiry event. Cache status counters are process-local; experiment results
  persist actual measured SQL/HTTP totals at finalization. Pre-crash in-memory
  counters are labeled unavailable, not reconstructed or invented.
- Routine stop preserves Compose volumes. Redis AOF `everysec` is configured,
  but persistence is not an unconditional guarantee or proof that cached data
  is trustworthy after reconnect.
- Windows `stop.ps1` terminates only the saved, identity-checked app PID; it is
  not a transaction-draining experiment cancel operation. PostgreSQL resolves
  its transaction. Use the run cancellation API to seal/drain an experiment;
  process death instead produces an INTERRUPTED run on restart.
- Native PostgreSQL and Redis-in-WSL acceptance is real process evidence, but **not Docker
  evidence**. The default Testcontainers path must fail, not skip, if Docker is
  missing. The author machine has no Docker runtime; see the evidence record.
- Real Redis shutdown, forced Java-process death and actual Chromium dashboard
  failure/recovery have dedicated coverage. Full Compose lifecycle, raw benchmark
  and screenshot gates are separate; consult the exact tagged CI result rather
  than treating native evidence as Docker execution. No performance result is
  inferred from a UI screenshot or a short demonstration.
- Browser automation currently targets pinned Chromium, not a cross-browser
  certification. Node/Bruno/Playwright are development dependencies only.
  Eight-second browser timeouts and capped polling retries never automatically
  resubmit a purchase. Export/live snapshots can be stale while disconnected.
- Run safety, dispatch completion and HTTP success are separate. NONE can
  conserve stock mathematically while overselling. Seeded jitter does not make
  scheduling deterministic; small latency percentiles are not robust SLAs.
- Run dispatch is capped at 120 seconds; existing requests, drain and bounded
  verification can extend end-to-end time. Failed finalization retains ownership
  until safe reconciliation or restart; cancellation never kills a DB transaction.
- Stale-reader pause and client response discard are labeled injected local
  faults. OUTAGE observes real Redis calls and waits for manual service recovery;
  no web endpoint stops processes or controls a Docker socket.
- At most 256 events survive per run, with explicit gaps and dropped counts.
  Runs/products/attempts/keys remain until an administrator manages the
  disposable database; this is not a public long-term retention service.
- Repeated trials use fixed case order, fresh cold fixtures and one JVM.
  Warmups do not eliminate JIT/GC/OS-load bias. Pooled raw samples are correlated;
  with fewer than 100 samples p99 is the maximum. No confidence interval,
  production speedup or strategy ranking is claimed from these small runs.
- Generated Compose walkthrough projects retain stopped containers and named
  volumes deliberately. Repeated invocation consumes disk until their owner
  explicitly manages them; there is no automatic destructive housekeeping.
- Presentation contains genuine screenshots and recording scripts, not a
  recorded video. Only Chromium is automated; no broad accessibility audit,
  other-browser support certification or public deployment is implied.
