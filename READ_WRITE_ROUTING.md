# Read/Write DB Routing — Library 3.0

## Problem

The goal of `read-write-seperation-library` is to offload read traffic
from the primary database to a read replica while keeping writes,
locks, and in-transaction reads on the primary — driven by a thin
annotation contract (`@ForceMasterRead`, `@StickyRead`) so call sites
express intent declaratively. In production this should translate to
materially lower primary load and a replica that earns its keep.

That outcome hasn't been realised. The library's underlying routing
engine made its DataSource decision at the wrong point in the Spring
lifecycle, so the routing intent expressed at the call site was
effectively ignored at the JDBC layer. Until now no consumer service
adopted the library productively — the replica has sat idle and the
primary has handled all traffic.

This document describes the engine swap that closes that gap, the
validation behind it, and the limitations that remain.

---

## Solution

Replace the in-house routing engine with Apache ShardingSphere-JDBC, keeping
the library's public surface (`@ForceMasterRead`, `@StickyRead`,
sticky-window mechanism, property contract) unchanged.

ShardingSphere parses each SQL statement **at execution time** — after Spring
has set up the tx context — and dispatches based on the SQL's intent: SELECT
to read DS, DML/DDL to write DS, lock-clause SELECT to write DS. This is the
routing primitive the in-house engine cannot do correctly.

Two new bridge aspects translate the library's ThreadLocal intent into
ShardingSphere's `HintManager` mechanism. Application code at the call site
is identical to today; consumer services migrate by bumping the library
version and configuring the read DS connection details.

```
       Application code
       (uses @ForceMasterRead, @StickyRead — unchanged)
            │
            ▼
   ┌────────────────────────────────────────────────────────┐
   │ Library 3.0 — intent layer                             │
   │  HintManagerBridgeAspect      reads ThreadLocals →    │
   │  StickyWriteRecorderAspect    emits HintManager hints  │
   └────────────────────────────────────────────────────────┘
            │
            ▼
   ┌────────────────────────────────────────────────────────┐
   │ ShardingSphere-JDBC — routing engine                   │
   │  SQL parser:  SELECT → read_ds,  DML → write_ds        │
   │  transactionalReadQueryStrategy: PRIMARY               │
   │  HintManager: per-thread write-route-only override     │
   └────────────────────────────────────────────────────────┘
            │
            ▼
        primary DB   |   replica DB
```

### Before vs after

| | Before (library 2.0.1) | After (library 3.0.0) |
|---|---|---|
| Engine | `AbstractRoutingDataSource` subclass | `ShardingSphereDataSource` (auto-configured) |
| Routing decision time | Connection acquisition (before tx context exists) | SQL statement execution (after tx context exists) |
| In-tx read-then-write | Reads can land on replica → stale | Pinned to primary via `transactionalReadQueryStrategy: PRIMARY` |
| `@ForceMasterRead` | Sets a ThreadLocal that the engine never reads | Drives `HintManager.setWriteRouteOnly()` per repo call |
| `@Lock(PESSIMISTIC_WRITE)` | `SELECT ... FOR UPDATE` may go to replica | Lock clause recognized by parser, routed write-only |
| Consumer integration | Component-scan + properties | Add dep + property keys (Spring Boot autoconfig) |

---

## Why ShardingSphere

| Alternative | Verdict | Reason |
|---|---|---|
| Stay with in-house engine | Reject | Architecturally broken — decides DS at connection-acquisition, before tx context exists. Not fixable without a rewrite. |
| Hand-rolled JDBC interceptor | Reject | Re-implements SQL parsing — ANTLR grammars, lock-clause recognition, JPQL-vs-native handling. High maintenance, low value. |
| Connection-pool proxy (pgcat / PgBouncer-rr / RDS Proxy) | Defer | Cleaner long-term architecture; cross-pod sticky natively via Redis. Trade-offs: new infrastructure to operate; loses `@ForceMasterRead` ergonomics; full migration cost. Worth re-evaluating after this lands. |
| ShardingSphere-JDBC | Adopt | SQL-aware out of the box; standard JDBC contract; lives in JVM (no new infra); `HintManager` maps cleanly to `@ForceMasterRead`. Apache project, stable. |

ShardingSphere costs ~25–30 MB of transitive dependencies and adds ~500 ms
to ~1 s of cold-start time for parser initialization. It does not solve
cross-pod sticky-window or replica lag-awareness — those are separate
concerns (see "Known gaps").

---

## Library changes

Version `2.0.1 → 3.0.0`.

```
Removed (broken engine + supporting cruft):
  config/RoutingDataSource.java                       AbstractRoutingDataSource subclass
  config/DataSourceConfig.java                        ShardingSphere builds Hikari pools internally
  context/dataSourceContext/DataSourceContextHolder   Replaced by HintManager
  enums/DataSourceType.java
  ReadWriteSeperationLibraryApplication.java          @SpringBootApplication in a library
  resources/application.properties

Added:
  aspect/HintManagerBridgeAspect.java                 ThreadLocals → HintManager.setWriteRouteOnly()
  aspect/StickyWriteRecorderAspect.java               afterCommit hook → refresh lastWriteTime
  resources/META-INF/spring.factories                 Spring Boot auto-configuration

Modified:
  config/DataSourceRoutingConfig.java                 Now builds ShardingSphereDataSource from YAML
  pom.xml                                             + shardingsphere-jdbc-core 5.4.1
  aspect/ForceMasterReadAspect.java                   @Component dropped (now @Bean via autoconfig)
  aspect/StickyReadAspect.java                        @Component dropped (now @Bean via autoconfig)
  config/properties/*.java                            @Configuration dropped (via @EnableConfigurationProperties)
```

### Consumer integration contract

The library activates only when `spring.datasource.routing.enabled=true`.
Properties consumer services configure:

```yaml
spring:
  datasource:
    write:
      url: jdbc:postgresql://primary:5432/db
      username: ...
      password: ...
      driver-class-name: org.postgresql.Driver
    read:
      url: jdbc:postgresql://replica:5432/db
      username: ...
      password: ...
      driver-class-name: org.postgresql.Driver
    routing:
      enabled: true
      sticky-writes:
        enabled: true
        window-ms: 5000
```

No `@ComponentScan` of library packages. No `@Import`. No application code
changes at call sites that use `@ForceMasterRead` / `@StickyRead` today.

---

## POC validation

The POC consumes the productionized library exactly the way a consumer
service will — add the dep, set the properties, done. ShardingSphere
comes in transitively through the library.

POC-side changes (compared to the prior hybrid harness):

```
Removed:
  hybrid/HintManagerBridgeAspect.java          Moved into the library
  hybrid/StickyWriteRecorderAspect.java        Moved into the library
  hybrid test config + ShardingSphere wiring   Library auto-configures itself

Modified:
  pom.xml                                      Library 2.0.1 → 3.0.0 (drop direct ShardingSphere dep)
  Integration test                             @DynamicPropertySource feeds Testcontainer URLs
                                               into the library's expected property keys
```

The integration test now mirrors the consumer-service contract verbatim.

---

## Tests

Two real Postgres containers (Testcontainers), identical schema, marker
columns distinguishing primary (`served_by='PRIMARY'`) and replica
(`served_by='REPLICA'`). The replica connection uses a `GRANT SELECT`-only
role — any misrouted write throws `permission denied` immediately rather
than silently succeeding.

```
mvn test → Tests run: 30, Failures: 0, Errors: 0
```

### Coverage

| Group | Scenarios | What it proves |
|---|---|---|
| Basic read routing (SELECT outside tx) | 3 | Replica receives derived finders, JPQL, and native SELECTs |
| Basic write routing (INSERT/UPDATE/DELETE) | 5 | Primary receives writes; sticky-window refreshes after each |
| Transactional behavior | 5 | tx pinning works for `@Transactional`, `@Lock(PESSIMISTIC_WRITE)`, `REQUIRES_NEW`, and `TransactionTemplate` |
| Force-master annotation | 2 | Method-level and class-level `@ForceMasterRead` both route to primary |
| Sticky-window mechanism | 7 | Read-your-own-write within window; expiry; refresh; `@Async` and raw-thread cross-thread behavior |
| Inherited Spring Data methods | 3 | `count`/`existsById`/`findAll(Pageable)` route to primary — documented trade-off from Spring Data's class-level `@Transactional(readOnly=true)` |
| Edge cases & robustness | 5 | Rollback skips `afterCommit`; Hibernate dirty-check gap; manual `HintManager` coexistence; exception cleanup (no ThreadLocal/HintManager leak) |

Per-scenario probe / pass-condition / regression-detection notes in
`TESTING.md`.

### Key behaviors confirmed

- All SELECTs outside a tx route to replica.
- All DML routes to primary regardless of source (JPQL, native, derived).
- `SELECT ... FOR UPDATE` from `@Lock(PESSIMISTIC_WRITE)` routes write-only
  even outside a wrapping tx.
- `@ForceMasterRead` annotation produces correct primary routing via
  `HintManager`; ThreadLocal cleared on both success and exception paths.
- Sticky-window pins reads to primary within the configured window after
  a write commits; expires correctly; refreshes correctly on subsequent
  writes.
- `@Async` and raw `new Thread()` cross-thread reads observe the JVM-global
  `lastWriteTime` and pin correctly.
- Rolled-back transactions do NOT refresh sticky-window (`afterCommit`
  correctly skipped).

---

## Known gaps and limitations

These are real and need separate work — not blockers for the library 3.0
rollout, but the team should be aware:

1. **Cross-pod sticky-window.** `lastWriteTime` is a `static volatile
   long`, scoped to one JVM. In a multi-pod deployment, a write on pod
   A does not refresh pod B's sticky window — read-your-own-write
   across pods returns stale data. Affects any flow behind a
   round-robin load balancer, Kafka consumers on different pods, and
   service-to-service flows. Mitigation path: Redis-backed
   `lastWriteTime` keyed by entity or tenant; Postgres LSN tracking for
   principled correctness. Both compose with this library cleanly —
   only the recorder/bridge change.

2. **Cross-entity bleed within a pod.** Any write refreshes the global
   timestamp; unrelated reads from any code path get pinned for the
   window duration. Under high write rates the replica receives minimal
   traffic. Same mitigation path as above (per-entity Redis keying).

3. **Replication lag is not measured.** The 5-second window is a guess,
   not a bound. If lag spikes above 5 s, reads can still be stale even
   inside the window. If lag is consistently under 100 ms, the window
   pins reads to primary much longer than necessary. Mitigation:
   connection-pool proxy with lag-aware routing, or LSN-based
   stickiness.

4. **`@Transactional(readOnly=true)` routes to primary.** Deliberate
   trade-off from `transactionalReadQueryStrategy: PRIMARY`. Per
   call-site fix: drop the wrapping annotation and let the SELECT
   autocommit.

5. **Inherited `JpaRepository` methods route to primary.** `count()`,
   `existsById()`, `findAll(Pageable)`, `findById()` etc. — Spring Data
   wraps these in a class-level `@Transactional(readOnly=true)` on
   `SimpleJpaRepository`. Same trade-off as #4. User-defined `@Query`
   methods and derived finders are unaffected.

6. **Hibernate dirty-check flush doesn't refresh sticky-window.** The
   recorder's pointcut matches `save*/delete*/@Modifying`. Dirty-check
   UPDATE happens on flush without those triggers. Subsequent reads
   outside the tx may be stale. Mitigation: prefer explicit `save()`
   in code paths needing read-your-own-write; future: hook Hibernate
   event listeners.

7. **No replica health-checking.** A dead replica continues receiving
   queries until config is updated and pods restart. Mitigation: proxy
   with health checks (pgcat) or sidecar.

8. **Not yet validated against a production service.** All tests run
   against the POC's Testcontainers harness. Production rollout needs
   integration into one consumer service, validation against an
   environment with a real replica (or local docker-compose with
   streaming standby), then a controlled rollout behind the
   `routing.enabled` flag.

---

## Next steps

1. Merge library 3.0 to `main`. Publish snapshot.
2. Integrate into one consumer service (smallest blast radius first;
   credentials-manager is the recommended pilot).
3. Validate routing in a staging environment with a real read replica
   provisioned, or via Testcontainers tests inside that service's CI.
4. Plan the distributed sticky-window fix — Redis-backed per-entity
   keying or LSN-based correctness. This is the highest-leverage
   follow-up once the engine swap is in production; it composes with
   the library cleanly.
