# read-write-routing-poc — `shardingsphere-only` branch

End-to-end POC proving that the same 15-scenario routing matrix the `main`
branch validates against the **hybrid** approach (ShardingSphere on top of
`read-write-seperation-library`) also passes with **no library dependency at
all** — ShardingSphere does the routing; a small local package
(`tech.vegapay.routingpoc.routing`) contributes annotations and the
sticky-window mechanism.

## TL;DR

- `read-write-seperation-library` is removed from `pom.xml`.
- Annotations (`@ForceMasterRead`, `@StickyRead`), routing context, and
  aspects all live in this module (~250 LOC, one package).
- Same call-site code, same `@Transactional` patterns, same test expectations.
- `mvn test → Tests run: 15, Failures: 0, Errors: 0`.

## Why this branch exists

The `main` branch demonstrates the **hybrid** integration: keep the in-house
library's annotation surface and ThreadLocal model, swap its broken
`RoutingDataSource` engine for ShardingSphere underneath, with two aspects
bridging the two. The argument was "the library brings useful API surface;
ShardingSphere just replaces the broken engine."

This branch tests a stronger claim: **the API surface itself is small enough
that the library doesn't have to exist as a dependency**. ShardingSphere
provides the routing primitive (`HintManager.setWriteRouteOnly()` +
SQL-aware DataSource); the annotations, the sticky-window state, and the
aspects that connect them are ~250 LOC that can live anywhere — in this
module, in a thin extras JAR, or directly in a consumer service.

If consumer services already depend on the library, this is the migration
target: drop the library version range entirely, copy these ~7 files into
either a new shared module or into each service.

## Architecture (this branch)

```
        Application code  (uses local annotations as before)
                    │
                    ▼
    ┌──────────────────────────────────────────────────────────┐
    │  tech.vegapay.routingpoc.routing  (this module, ~250 LOC) │
    │   • @ForceMasterRead  → RoutingContext.forceMaster         │
    │   • @StickyRead       → RoutingContext.stickyReadOverride  │
    │   • RoutingContext.lastWriteTime (static volatile, JVM-wide) │
    │   • ForceMasterReadAspect / StickyReadAspect drive ThreadLocals │
    │   • StickyWriteRecorderAspect refreshes lastWriteTime        │
    │   • HintManagerBridgeAspect: ThreadLocal → HintManager hint  │
    └──────────────────────────────────────────────────────────┘
                    │
                    ▼
    ┌──────────────────────────────────────────────────────────┐
    │  Apache ShardingSphere-JDBC 5.4.1                        │
    │   • @Primary DataSource                                  │
    │   • SQL parser: SELECT → read_ds, DML → write_ds         │
    │   • HintManager.setWriteRouteOnly() overrides to write   │
    │   • transactionalReadQueryStrategy: PRIMARY              │
    └──────────────────────────────────────────────────────────┘
                    │
                    ▼
            primary DB   |   replica DB
```

## What's in `tech.vegapay.routingpoc.routing`

```
routing/
├── annotation/
│   ├── ForceMasterRead.java       ← pin reads in method/class scope to primary
│   └── StickyRead.java            ← widen sticky window for this thread (ms)
├── context/
│   └── RoutingContext.java        ← forceMaster ThreadLocal +
│                                     stickyReadOverride ThreadLocal +
│                                     lastWriteTime static volatile
├── aspect/
│   ├── ForceMasterReadAspect.java ← @Around on @ForceMasterRead → set/clear ThreadLocal
│   ├── StickyReadAspect.java      ← @Around on @StickyRead → set/restore override
│   ├── StickyWriteRecorderAspect.java
│   │                              ← @AfterReturning on save*/delete*/@Modifying
│   │                                → registers afterCommit (or autocommit-marks)
│   │                                  RoutingContext.markWrite()
│   └── HintManagerBridgeAspect.java
│                                  ← @Around on every Spring Data repo method
│                                    → if forceMaster OR within sticky window:
│                                      open HintManager + setWriteRouteOnly()
└── config/
    └── StickyWriteProperties.java ← @ConfigurationProperties
                                     spring.datasource.routing.sticky-writes.*
```

`RoutingContext` collapses the library's three holder classes
(`RoutingContextHolder` / `StickyReadContext` / `StickyWriteContext`) into
one — they're all read at the same call site (the bridge aspect), so there's
no reason to split them.

## What changed vs `main`

```
Removed
  pom.xml                                ← drop tech.vegapay.readwriteseperationlibrary dep
  src/main/.../hybrid/                   ← whole package retired
  src/test/.../hybrid/                   ← whole package retired
  LIBRARY_INTEGRATION.md                 ← hybrid-specific doc, no longer applies here

Added
  src/main/java/tech/vegapay/routingpoc/routing/        ← new local routing package
  src/main/java/tech/vegapay/routingpoc/UserService.java ← service surface
  src/test/java/tech/vegapay/routingpoc/routing/        ← test config + integration test

Modified
  README.md                              ← describes this branch's variant
  TESTING.md                             ← refreshed to reference local classes
```

Net change: library dependency dropped, ~250 LOC added in `routing/`,
test-side reset got 8 lines shorter (no more reflection on the library's
private static field).

## Quiet correctness fix

The library's `StickyReadAspect` uses `@Before` and never clears the
override ThreadLocal — the value leaks until something else overwrites or
clears it. This branch's `StickyReadAspect` uses `@Around` and restores the
prior override in `finally`, so per-thread sticky-read state is properly
scoped to the annotated method. Not a behavior change for the 15-scenario
matrix (the test resets between scenarios), but worth noting if call-site
code starts nesting `@StickyRead` methods.

## Trade-offs vs the hybrid (`main`)

| Concern | Hybrid (main) | ShardingSphere-only (this branch) |
|---|---|---|
| External dep | `read-write-seperation-library` 2.0.1+ | none beyond ShardingSphere |
| Annotation surface | library-owned | local to this module |
| Sticky-window scope | JVM-global (library's static) | JVM-global (this module's static) |
| Scenarios passing | 15/15 | 15/15 (including 8, 13, 14 sticky scenarios) |
| Scenario 5 (`@Transactional(readOnly=true) → PRIMARY`) | unfixed | unfixed (same `transactionalReadQueryStrategy=PRIMARY` trade-off) |
| Migration cost from in-house engine | library version bump | drop library dep, copy 8 files in once |

## Running

```
cd /Users/dwivedi_utkarsh/Vegapay/read-write-routing-poc
git checkout shardingsphere-only
mvn test
```

Requires Docker. First run pulls `postgres:14` (~1–2 min); subsequent runs
take ~30 s. No local-Maven install of `read-write-seperation-library` is
required on this branch.

## Dependencies (this branch)

- Spring Boot 2.7.18, Java 11
- Apache ShardingSphere-JDBC 5.4.1
- Testcontainers 1.21.3

(No `tech.vegapay.readwriteseperationlibrary` line in `pom.xml`.)

## Files to look at first

1. `src/main/java/tech/vegapay/routingpoc/routing/aspect/HintManagerBridgeAspect.java`
   — the actual bridge: one place that reads RoutingContext and decides
   whether to pin.
2. `src/main/java/tech/vegapay/routingpoc/routing/context/RoutingContext.java`
   — the entire routing state model, ~60 LOC.
3. `src/test/java/tech/vegapay/routingpoc/routing/RoutingIntegrationTest.java`
   — the 15-scenario matrix. Per-scenario expectations are unchanged from
   `main`.

## Relation to `main`

The `main` branch documents the **hybrid** approach — ShardingSphere as the
engine, the in-house `read-write-seperation-library` retained as the
annotation surface and ThreadLocal contract, with two bridge aspects
translating between them. That path is a valid migration target if your
services already depend on the library.

This branch demonstrates the stronger claim that the library itself is
not load-bearing: the routing module is small enough to ship inside each
consumer (or as one shared `routing-extras` module) directly. The two
paths are not mutually exclusive — pick whichever has the lower migration
cost for your services. Either way, the routing primitives live above
ShardingSphere, not inside it.
