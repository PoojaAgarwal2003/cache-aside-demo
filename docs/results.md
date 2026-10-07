# Measured observations and capture provenance

Recorded **2026-10-07**, app **0.6.0**, from the actual packaged JAR. These
results are local samples, **not strategy rankings, scalability claims or SLAs**.
The screenshot and benchmark use different, explicitly disclosed delay settings.

## Repeated zero-delay comparison

Windows x64 `10.0.28000`, Intel Xeon Platinum 8370C at 2.80GHz, 16 logical CPUs,
68,665,831,424 bytes RAM (about 64 GiB). Temurin/OpenJDK 17.0.20.1+1, maximum JVM
heap 17,171,480,576 bytes; PostgreSQL 16.15 native Windows and Redis 7.4.11 in
the owned WSL distro. Node 22.17.0 orchestrated the fixed local HTTP runner.
This is **native/WSL evidence, not local Docker execution**.

True `benchmark` profile, **read delay 0ms / purchase delay 0ms**, rate limiter
enabled with fresh distinct clients. Two whole-run warmups excluded, then five
measured COMPARE trials. Each trial: five fresh independent cases, 50 buyers,
concurrency 10, stock 10, quantity 1, seed 1, jitter 0, dispatch budget 60s.
Fixed case order: NONE, ATOMIC_SQL, PESSIMISTIC, OPTIMISTIC, REDIS_ASSISTED.
All 50 buyers per case were dispatched, with no errors/unknown HTTP outcomes.
Business rejections remain counted separately from successes.

| Strategy | Raw measured attempts | All-attempt p50 / p95 / p99 (ms) | 2xx samples / p95 (ms) | Observed final stock, five trials |
|---|---:|---|---|---|
| NONE **unsafe** | 250 | 10.42 / 33.06 / 41.77 | 52 / 41.77 | 0, 0, 0, -1, -1 |
| ATOMIC_SQL | 250 | 10.35 / 38.89 / 86.26 | 50 / 64.54 | 0, 0, 0, 0, 0 |
| PESSIMISTIC | 250 | 10.62 / 33.08 / 46.69 | 50 / 46.69 | 0, 0, 0, 0, 0 |
| OPTIMISTIC | 250 | 9.82 / 42.23 / 50.46 | 50 / 45.38 | 0, 0, 0, 0, 0 |
| REDIS_ASSISTED | 250 | 27.92 / 57.04 / 76.73 | 50 / 73.34 | 0, 0, 0, 0, 0 |

Every protected case committed 10 unique unit sales and passed the ledger/
stock invariants. NONE oversold in **two of five** measured trials, committing
11 units with stock -1; the other three samples do not make it safe.
No artificial barrier was added to these benchmark runs.

Percentiles are computed from raw samples by nearest rank, not averaged trial
percentiles. All-attempt latency includes rapid business rejections; it is not
the success-only distribution. With only 50-52 success samples, p99 is the
maximum. JIT/GC, fixed order, native/WSL transport and host load remain biases.
Per-trial SQL execution counts, retries, errors and run-wide throughput
denominators are retained in the raw exports. They are not isolated case
service capacity and are not summed to fabricate aggregate throughput.

The actual PowerShell 5.1 wrapper also ran COLD_WARM with one excluded warmup
and two measured trials. Sources were DATABASE then REDIS_CACHE each time;
HTTP durations were **18.67/7.19ms** and **15.77/6.47ms**, with one actual
USER_READ SQL call per run. Four observations are not evidence of a general
cache-speedup factor. Equivalent Bash wrapper execution is covered by Linux CI.

## Genuine screenshots

The presentation test captured the actual dashboard at 1440px wide and 390px
mobile width with reduced motion. Its fixture uses **200ms read / 25ms purchase
synthetic delays**; these screenshots are not the zero-delay results above.

- [Comparison](assets/comparison.png): run `0386c435-81e2-4b1d-9975-5a4fc74309b3`.
  All five happened to sell 10 with stock 0 in this capture; NONE's unsafe warning
  stays visible. Do not replace this result with the benchmark's overselling.
- [Cold/warm detail](assets/cold-warm.png) and [mobile](assets/mobile.png):
  run `e431c145-2ff5-4f7a-b687-dc7e1a6a4d93`, actual warm request selected.
- [Capture manifest](evidence/milestone-6/capture.json): UTC time, app version,
  delays, image byte sizes and SHA-256 hashes. No mocked response or altered
  counter is used. [Recording scripts](demo.md) explain how to reproduce views.

## Raw evidence and verification

[Benchmark manifest](evidence/milestone-6/benchmark.json) includes all seven run
IDs, warmup/measurement labels, host/runtime information, per-trial metrics and
pooled samples. [Raw evidence ZIP](evidence/milestone-6/raw-evidence.zip) contains:
the two screenshot run exports; all seven comparison run exports; wrapper
cold/warm trials; and the complete seven-scenario native lifecycle evidence.
Selected generated evidence is intentionally committed; routine outputs stay
ignored. No logs, credentials, generated Java arguments or personal paths are
included in this published archive.

Archive SHA-256:
`AC229C7261703B706FC3A74D090CF4EEDAEE342EBA585AFCC4649667B9A4DECA`.

Lifecycle evidence includes actual Redis process identity change with the
same retained marker, fallback/admission/limiter differences, quantity-two
same-key replay, and completed/interrupted exports surviving two app restarts.
An interrupted run remains INCONCLUSIVE, with unknown duration/throughput.
See [acceptance evidence](evidence.md) for gates and the distinction between
native validation and the exact published commit's container/Compose CI.
