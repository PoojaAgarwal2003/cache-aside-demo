# Scope and correctness limits

- This is a **single application-instance local educational lab**, not a
  production inventory service or distributed-system certification.
- Milestone 2 implements all five inventory strategies and advisory Redis
  admission. No product-read cache, limiter, circuit readiness, experiment runner
  or dashboard is claimed yet. `/status` reports this honestly.
- No real payment is performed. Atomic stock/ledger commit and durable key
  deduplication do not imply exactly-once request execution or payment delivery.
- `X-Client-Id` is a spoofable lab identity, not authentication. Keys have meaning
  only within that claimed identity. Loopback binding and same-origin checks
  are local safety boundaries, not a public deployment security design.
- Stock conservation for protected purchases assumes an isolated fixture with
  **no concurrent unsafe or administrative stock changes**. Ordinary product
  PATCH can intentionally reset stock after drain. Local fixture guards reject
  API resets during in-flight purchases; direct SQL is outside those guards.
  A future runner adds run lifecycle/cancellation controls.
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
- LISTEN/NOTIFY triggers already emit after commit, but there is no application
  listener yet. Future invalidation will be best-effort, not durable CDC. Future
  cached reads will be eventual, not linearizable; TTL is not an exact
  commit-to-freshness deadline.
- Routine stop preserves Compose volumes. Redis AOF `everysec` is configured,
  but persistence is not an unconditional guarantee or proof that cached data
  is trustworthy after reconnect.
- Windows `stop.ps1` terminates only the saved, identity-checked app PID; it is
  not a transaction-draining experiment cancel operation. PostgreSQL resolves
  its transaction. Graceful runner cancellation belongs to milestone 4.
- Native PostgreSQL and Redis-in-WSL acceptance is real process evidence, but **not Docker
  evidence**. The default Testcontainers path must fail, not skip, if Docker is
  missing. The author machine has no Docker runtime; see the evidence record.
- Real Redis shutdown and forced Java-process death are tested now. Browser,
  full Compose walkthrough, visual comparison and benchmark acceptance remain
  for later milestones. There are no invented screenshots or performance results.
