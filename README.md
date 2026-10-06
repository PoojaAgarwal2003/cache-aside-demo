# FlashSale Lab

**50 buyers. 10 items. Which strategies prevent overselling, and what changes
when Redis fails?**

`cache-aside-demo` is a **single application-instance, localhost-only learning
lab**, built with Java, real PostgreSQL and Redis. There are no payments,
customer data, cloud services or production-readiness claims.

**Milestone 5 of 6 is implemented:** all five inventory strategies and durable
idempotency, plus typed product caching, fenced invalidation, listener recovery,
bounded rebuilds, separate Redis breakers and sliding-window rate limiting.
The persisted experiment runner adds real loopback HTTP load, isolated fixtures,
drain/cancellation, ledger reconciliation, six guided scenarios and JSON export.
The local-asset dashboard adds six guided views, five-way comparison, showcase
layout and an asserted Bruno collection. **Pause before milestone 6**, which
covers repeatable benchmarks and polished presentation/recordings.
[The roadmap](docs/roadmap.md) defines 22 meaningful commits and delivery gates.
[Evidence](docs/evidence.md) distinguishes native PostgreSQL/Redis results from
Docker-backed CI. [The supplied specification](docs/specification.txt)
is the full target, not a list of already implemented features.

[API walkthrough](docs/api.md) | [Architecture](docs/architecture.md) |
[Operating guide](docs/operations.md) | [Limitations](docs/limitations.md)

After starting the app, open `http://127.0.0.1:8080/` (or your configured port).
[Dashboard guide](docs/dashboard.md)
| [Bruno API walkthrough](docs/bruno.md)

```powershell
# With the demo app running in another terminal:
.\scripts\flash-sale.ps1 -Compare -OutFile comparison.json
.\scripts\fire-requests.ps1 -Json '{"scenario":"COLD_WARM"}' -OutFile reads.json
```

[Experiments](docs/experiments.md) explains parameters, cancellation, scenarios,
cursor events, measured SQL work and why HTTP counts alone cannot prove safety.

| Strategy | What enforces the decision |
|---|---|
| NONE | **Intentionally unsafe**, demo-only unlocked check/decrement; isolated fixtures |
| PESSIMISTIC | PostgreSQL row lock across check, synthetic work and decrement |
| OPTIMISTIC | Stock/version predicate; fresh transactions, at most 20 attempts |
| ATOMIC_SQL (default) | Single PostgreSQL conditional decrement |
| REDIS_ASSISTED (`redis` alias) | Epoch-scoped advisory admission, then the same authoritative SQL predicate |

All five share the persisted request/ledger boundary. A Redis rejection is not
proof of database exhaustion. See [the admission contract](docs/stock-admission.md)
for compensation, drift, fixed TTLs and drain-only reconciliation.

Product reads cache positive and absent DTOs, never inventory decisions.
[The cache contract](docs/product-cache.md) explains fixed TTLs, generation and
epoch fencing, invalidate-only notifications and eventual-consistency limits.
The limiter accepts 10 product/purchase requests per controlled client per
10 seconds; Redis outages explicitly bypass it without removing DB backpressure.

## Prerequisites and pinned stack

| Component | Selected version |
|---|---|
| Java language/runtime | Java 17; verified toolchain Temurin 17.0.20.1+1 |
| Spring Boot | 4.1.1 |
| Gradle wrapper | 9.8.0, distribution SHA-256 verified |
| Resilience4j | 2.4.0, core APIs (not Boot 3 auto-configuration) |
| PostgreSQL | 16.15, digest-pinned `bookworm` image |
| Redis | 7.4.11, digest-pinned `bookworm` image |
| Testcontainers BOM | 2.0.5 |
| JUnit | 5.14.4, explicitly retained instead of Boot's JUnit 6 default |
| JDBC / Flyway / Hibernate / Awaitility | Boot-managed; exact graph in `gradle.lockfile` |
| Browser/API development tests only | Node 22+, Playwright 1.63.0, Bruno CLI 4.2.0; `package-lock.json` |

Install a JDK 17 and Docker with Linux containers and Compose v2. Set
`JAVA_HOME` to the JDK, with its `bin` on `PATH`. No global Gradle, Node, Bash
or frontend package installation is required to run the app on Windows.
Only the separate browser/API development gate needs Node and Chromium:

```powershell
npm.cmd ci
npm.cmd exec -- playwright install chromium
.\scripts\verify.ps1 -BrowserOnly
```

Bash: `npm ci`, `npm exec -- playwright install --with-deps chromium`,
then `./scripts/verify.sh --browser-only`. These tests start the actual packaged
JAR with isolated real PostgreSQL/Redis fixtures, not mocked successful APIs.

For a checkout inside OneDrive, set `FLASHSALE_BUILD_DIR` to a dedicated local,
non-synced directory before building (for example
`$env:FLASHSALE_BUILD_DIR = "$env:LOCALAPPDATA\FlashSaleLab\build"`).
This avoids sync-client locks on Gradle's transient binary test results.

See [dependency decisions and official sources](docs/dependencies.md). Do not
silently return to the old Boot 3.3.5 stack or upgrade Java to hide incompatibility.

## Run locally

From this repository in PowerShell:

```powershell
.\scripts\start.ps1
# In another terminal:
Invoke-RestMethod http://127.0.0.1:8080/status
.\scripts\stop.ps1
```

On Linux/macOS:

```sh
./scripts/start.sh demo
# Ctrl+C stops the foreground app; then:
./scripts/stop.sh
```

The application binds to `127.0.0.1:8080`, PostgreSQL to `127.0.0.1:55432`,
Redis to `127.0.0.1:56379`. All credentials shown are **disposable local lab
values**, not secrets. Configure ports/password through environment variables;
Compose can also read `.env` copied from `.env.example`. A direct `bootRun`
does not load `.env` automatically.

Ctrl+C stops the foreground application. `docker compose stop` stops only this
project's services and **preserves named volumes**. Never use `down -v` as a
routine stop or test step.

```powershell
.\gradlew.bat test                # Fast unit tests, no Docker
.\gradlew.bat integrationTest     # Real PostgreSQL AND Redis; Docker prerequisite
.\gradlew.bat check bootJar       # All current milestone checks + executable JAR
```

An unavailable container runtime must fail acceptance, never silently skip it.
The dashboard, browser suite and repeated benchmark presentation belong
to later milestones; there is no mock dashboard or invented screenshot.
