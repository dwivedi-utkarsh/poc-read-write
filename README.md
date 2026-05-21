# read-write-routing-poc

End-to-end POC proving the **hybrid read/write routing approach**:
the in-house `read-write-seperation-library` keeps the annotation surface
and sticky-window semantics it already has; Apache ShardingSphere-JDBC
sits underneath as the actual routing engine.

## TL;DR

Same annotations engineers use today (`@ForceMasterRead`, `@StickyRead`).
Same `StickyWriteContext`, same `@Transactional` patterns. ShardingSphere
makes the routing decision via SQL parsing. A small bridge translates
between the two.

Result: every scenario in the 15-test matrix passes, including the ones
that don't currently work in production (read-routing to replica,
read-your-own-write across threads, etc.).

```
mvn test     →    Tests run: 15, Failures: 0, Errors: 0
```

---

## What's actually happening

```
       Application code  (uses library annotations as before)
                   │
                   ▼
   ┌─────────────────────────────────────────────────────────┐
   │  In-house library — intent layer (unchanged)            │
   │   • @ForceMasterRead → RoutingContextHolder.FORCE_MASTER│
   │   • @StickyRead      → StickyReadContext.windowMs       │
   │   • StickyWriteContext.lastWriteTime (JVM-static)       │
   └─────────────────────────────────────────────────────────┘
                   │
                   ▼   HintManagerBridgeAspect  (the bridge)
                   │   reads library ThreadLocals →
                   │   emits HintManager.setWriteRouteOnly()
                   ▼
   ┌─────────────────────────────────────────────────────────┐
   │  ShardingSphere — mechanism layer                       │
   │   • @Primary DataSource                                 │
   │   • SQL parser: SELECT → read_ds, DML → write_ds        │
   │   • Honors HintManager hints as a write-only override   │
   └─────────────────────────────────────────────────────────┘
                   │
                   ▼
            primary DB   |   replica DB
```

Two new aspects make the integration work. Both are small (~50 LOC each)
and would move into the library itself in a productionized version:

- **`HintManagerBridgeAspect`** — `@Around` on every Spring Data repository
  method. Reads the library's ThreadLocals (`isForceMaster()`,
  `StickyReadContext`, `StickyWriteContext`). When any signal says "pin to
  primary", opens a `HintManager` for the call.
- **`StickyWriteRecorderAspect`** — `@AfterReturning` on repository
  `save*` / `delete*` / `@Modifying`. Calls `StickyWriteContext.markWrite()`
  (registered as `afterCommit` synchronization if a Spring tx is active,
  invoked directly otherwise). This is the piece that makes the sticky
  window actually have a `lastWriteTime` to compare against.

---

## Why ShardingSphere

ShardingSphere-JDBC is an Apache project that does read/write splitting by
parsing each SQL statement at execution time (after Spring has finished
setting up the transaction context, which is the part that matters).
Compared to building the routing engine in-house:

- SQL-aware out of the box: handles `SELECT … FOR UPDATE` from `@Lock`,
  native queries, JOINs, INSERT/UPDATE/DELETE — without case-by-case logic.
- Configured via one YAML block. Two URLs in, one routing DataSource out.
- Same JDBC contract as any other DataSource — JPA, Hibernate, Spring
  Boot all consume it without modification.

It doesn't ship sticky-window read-your-own-write or any annotation
surface, which is why we keep the in-house library on top. Use each tool
for what it's good at.

---

## Test harness

Two real Postgres containers (Testcontainers), identical schema, different
marker data:

| | Primary container | Replica container |
|---|---|---|
| Connection user | `app` (table owner, full privs) | `readonly_app` (`SELECT` only) |
| `users.served_by` value | `'PRIMARY'` on every row | `'REPLICA'` on every row |
| Write attempt would | succeed | throw `permission denied for table users` |

Every read in a test asserts which DB served it by looking at the
`served_by` marker. Writes are verified via a side-channel `JdbcTemplate`
that talks directly to the primary container. Misrouted writes fail
loudly — there is no silent stale-read failure mode.

See [TESTING.md](TESTING.md) for the scenario-by-scenario walkthrough.

---

## Layout

```
src/
├── main/java/tech/vegapay/routingpoc/
│   ├── RoutingPocApplication.java       ← @SpringBootApplication + @EnableAsync
│   ├── User.java                        ← entity with the served_by marker column
│   ├── UserRepo.java                    ← every query shape the matrix exercises
│   └── hybrid/
│       ├── HybridUserService.java       ← service surface, uses library annotations
│       ├── HintManagerBridgeAspect.java ← reads library ThreadLocals → emits HintManager
│       └── StickyWriteRecorderAspect.java ← refreshes StickyWriteContext.lastWriteTime
└── test/
    ├── java/tech/vegapay/routingpoc/hybrid/
    │   ├── ShardingSphereTestConfig.java  ← builds the ShardingSphere DataSource
    │   ├── HybridTestConfig.java          ← @Imports library aspects + the bridge
    │   └── HybridRoutingIntegrationTest.java
    └── resources/
        ├── application-test.yml
        └── init/
            ├── primary.sql                ← 'PRIMARY' marker rows
            └── replica.sql                ← 'REPLICA' rows + readonly_app role
```

---

## Running

```
cd /Users/dwivedi_utkarsh/Vegapay/read-write-routing-poc
mvn test
```

Requires Docker. First run pulls `postgres:14` (~1–2 min); subsequent
runs take ~30 s.

---

## Dependencies

- Spring Boot 2.7.18, Java 11 — matches LOS / onboarding / credential-manager / library
- Apache ShardingSphere-JDBC 5.4.1
- Testcontainers 1.21.3
- `tech.vegapay.readwriteseperationlibrary:read-write-seperation-library:2.0.1-SNAPSHOT`
  — `mvn install -DskipTests` from `vegapay-library/read-write-seperation-library` if not in local repo

---

## Toward production

The POC has all the moving parts. To ship, they move from this test
module into the library itself. See [LIBRARY_INTEGRATION.md](LIBRARY_INTEGRATION.md)
for the file-by-file walkthrough — which library files stay untouched,
which two new files get added, what gets replaced in
`DataSourceRoutingConfig`, what gets deleted, and the dependency bump.

Consumer services (LOS, onboarding, credential-manager) migrate by
bumping the library version. No code changes at call sites. The same
`spring.datasource.write.*` / `spring.datasource.read.*` properties they
already set drive ShardingSphere instead of the broken `RoutingDataSource`.
