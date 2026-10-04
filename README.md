# FlashSale Lab

**50 buyers. 10 items. Which strategies prevent overselling, and what changes
when Redis fails?**

`cache-aside-demo` is a **single application-instance, localhost-only learning
lab**, built with Java, real PostgreSQL and Redis. There are no payments,
customer data, cloud services or production-readiness claims.

**Development status:** milestone 1 of 6 is in progress. This is not yet the
complete visual/cache lab. [The roadmap](docs/roadmap.md) defines 21 meaningful
commits, delivery gates and pauses. [The supplied specification](docs/specification.txt)
is the full target, not a list of already implemented features.

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

Install a JDK 17 and Docker with Linux containers and Compose v2. Set
`JAVA_HOME` to the JDK, with its `bin` on `PATH`. No global Gradle, Node, Bash
or frontend package installation is required on Windows.

For a checkout inside OneDrive, set `FLASHSALE_BUILD_DIR` to a dedicated local,
non-synced directory before building (for example
`$env:FLASHSALE_BUILD_DIR = "$env:LOCALAPPDATA\FlashSaleLab\build"`).
This avoids sync-client locks on Gradle's transient binary test results.

See [dependency decisions and official sources](docs/dependencies.md). Do not
silently return to the old Boot 3.3.5 stack or upgrade Java to hide incompatibility.

## Run locally

From this repository in PowerShell:

```powershell
docker compose up -d --wait
.\gradlew.bat bootRun --args="--spring.profiles.active=demo"
```

On Linux/macOS:

```sh
docker compose up -d --wait
./gradlew bootRun --args='--spring.profiles.active=demo'
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
.\gradlew.bat integrationTest     # Real PostgreSQL; Docker prerequisite
.\gradlew.bat check bootJar       # All current milestone checks + executable JAR
```

An unavailable container runtime must fail acceptance, never silently skip it.
The full dashboard, cache, other purchase strategies and browser suite belong
to later milestones; there is no mock dashboard or invented screenshot.
