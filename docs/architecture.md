# Architecture: delivered slice and future boundaries

**One host application instance, one dedicated PostgreSQL database, one Redis
service.** Milestone 1 implements only the database-backed vertical slice.
Redis is configured in Compose but is not consulted by the current API.

```mermaid
flowchart LR
    Client[Local HTTP client] --> Boundary[Loopback / origin / size / demo gate]
    Boundary --> Products[Product API: JPA]
    Boundary --> Purchase[Purchase API: JdbcTemplate]
    Products --> DB[(PostgreSQL)]
    Purchase --> DB
    DB --> Version[BEFORE UPDATE version trigger]
    DB --> Notify[Committed NOTIFY hint]
    Notify -. listener in milestone 3 .-> Redis[(Redis: not active in milestone 1)]
```

## Product path

```mermaid
sequenceDiagram
    participant Client
    participant API
    participant PostgreSQL
    Client->>API: GET /products/id
    API->>PostgreSQL: Authoritative product query
    PostgreSQL-->>API: DTO data or absent
    API-->>Client: DATABASE envelope, 200 or 404
    Client->>API: PATCH supplied fields
    API->>PostgreSQL: JPA optimistic UPDATE (old version predicate)
    PostgreSQL->>PostgreSQL: Trigger assigns OLD.version + 1
    API->>PostgreSQL: Flush / refresh / commit
    PostgreSQL-->>API: Actual version and timestamp
    API-->>Client: Committed product DTO
```

The trigger overrides proposed version values for **all ordinary SQL updates**,
including JDBC and external SQL. Hibernate's proposed +1 agrees with the trigger;
it is not an additional increment. An external SQL update makes an already-loaded
JPA version stale. There is no auto-DDL mutation, production inventory CHECK, or
reseed-on-startup. Public mutations validate nonnegative starting/reset stock.

## Purchase boundary

```mermaid
sequenceDiagram
    participant Client
    participant API
    participant PostgreSQL
    Client->>API: POST purchase + client/key
    API->>API: Validate / canonical fingerprint / hash key
    API->>PostgreSQL: Begin bounded READ COMMITTED transaction
    API->>PostgreSQL: INSERT unique scoped claim ON CONFLICT DO NOTHING
    alt Existing committed claim
        PostgreSQL-->>API: Original outcome or fingerprint conflict
    else New claim
        API->>API: Explicit synthetic work delay
        API->>PostgreSQL: UPDATE stock WHERE stock >= quantity RETURNING stock,version
        API->>PostgreSQL: Complete request result and SOLD ledger row
    end
    API->>PostgreSQL: Commit (deferred terminal-claim check)
    PostgreSQL-->>API: Commit acknowledged
    API-->>Client: Result, purchaseId, requestId, replay flag
```

The `purchase_ledger` view selects SOLD rows from `purchase_requests`. This
single-table boundary avoids independently updating request and ledger copies.
A uniqueness constraint prevents duplicate claims. Claim contention waits for
the transaction, not an application-memory map. Failed transactions remove the
claim and inventory change together. Unknown commit outcomes return a retryable
error; repeating the same scoped key resolves what actually committed.

## Later cache/recovery work (not implemented yet)

Milestone 3 will insert typed cache lookups before product queries, keep purchase
decisions in PostgreSQL, invalidate after commit, and use a dedicated LISTEN
connection plus generation/epoch fencing. Recovery must rotate the epoch and
verify listener/Redis health **before** enabling cache readiness. A CLOSED
breaker alone will not imply safe cache data. These are roadmap requirements,
not current capabilities.

## Feature-to-code map

| Delivered feature | Code |
|---|---|
| Boot/toolchain/dependencies | `build.gradle`, `gradle/wrapper`, `gradle.lockfile` |
| Bounded defaults/profiles | `src/main/resources/application*.yml` |
| Product schema, version and committed notifications | `db/migration/V1__authoritative_products.sql` |
| Request uniqueness and terminal ledger | `db/migration/V2__purchase_requests_and_ledger.sql` |
| Product DTOs, validation, optimistic CRUD | `product/` |
| Atomic purchase and replay boundary | `purchase/PurchaseService.java` |
| Canonical identity/fingerprint | `purchase/PurchaseRequest.java` |
| Request safety, tracing and error mapping | `web/` |
| Database/HTTP concurrency and rollback evidence | `MilestoneOneAcceptanceTest.java` |
| Upgrade/restart and default read-only mode | `StartupAcceptanceTest.java` |
| PowerShell-first operation | `scripts/` |

Java package paths above are relative to
`src/main/java/com/example/cacheaside`; SQL paths are relative to
`src/main/resources`. Integration suites are under `src/integrationTest`.
