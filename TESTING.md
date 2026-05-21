# Testing — read-write-routing-poc

Walks through the 15-scenario matrix the hybrid is tested against,
explaining what each scenario probes, what result we expect, and why.

## How routing is detected

Two real Postgres containers, identical schema, different marker data:

| | Primary container | Replica container |
|---|---|---|
| Connection user | `app` (owner, full privileges) | `readonly_app` (`GRANT SELECT` only) |
| `served_by` marker | `'PRIMARY'` for every row | `'REPLICA'` for every row |
| Receives writes | yes | misrouted writes throw `permission denied` |

A read tells us which DB served it by returning the marker column. A
write tells us where it landed: if it lands on primary, a side-channel
`JdbcTemplate` confirms the row is there; if it lands on replica, the
permission-denied exception fires immediately. There is no silent
stale-read failure mode.

`@BeforeEach` resets alice's email on primary and clears every
ThreadLocal / sticky-window flag so each scenario starts from a known
baseline.

---

## The 15 scenarios

### 1. `findByEmail` outside any user transaction → **REPLICA**

What it probes: the simplest plausible read. A derived finder, no
`@Transactional` declared by application code.

Why it's load-bearing: this is the default read pattern in the audited
consumer services.

How the hybrid achieves it: ShardingSphere's SQL parser sees a SELECT
outside any tx → load balancer routes to `read_ds`. The bridge aspect
runs but no library ThreadLocal is set, so no hint is emitted.

### 2. JPQL `@Query` outside any user transaction → **REPLICA**

What it probes: same as scenario 1 but with an explicit JPQL string
instead of a derived finder. Many consumer services use hand-written
JPQL for joins.

How the hybrid achieves it: query shape doesn't matter to ShardingSphere
— the parser identifies SELECT regardless of source.

### 3. `@Modifying` UPDATE outside any user tx → **PRIMARY**

What it probes: a Spring Data write via `@Modifying`. The repo method
has its own `@Transactional` (required by Spring Data for `@Modifying`),
but the caller doesn't.

How the hybrid achieves it: SQL parser sees UPDATE → routes to
`write_ds`. `StickyWriteRecorderAspect` fires `afterCommit` to update
`lastWriteTime` for any subsequent sticky reads.

### 4. `save()` outside any user tx → **PRIMARY**

What it probes: standard JPA persist via `JpaRepository.save()`. The
side-channel `JdbcTemplate` against primary confirms the row landed.

How the hybrid achieves it: same path as scenario 3 (INSERT instead of
UPDATE).

### 5. `@Transactional(readOnly = true)` containing a SELECT → **PRIMARY** ⚠️

What it probes: the canonical "offload to replica" pattern.

How the hybrid handles it: ShardingSphere is configured with
`transactionalReadQueryStrategy: PRIMARY`, which pins every read inside
any JDBC tx to `write_ds`. This is a deliberate config choice — it makes
scenarios 6 and 7 (writes inside a tx with prior reads) safe by
guaranteeing the reads see the just-written data.

The trade-off: Spring's `readOnly = true` hint isn't honored as "send to
replica" the way the in-house library claimed to. To get replica
offload, either drop the `@Transactional(readOnly = true)` at the call
site (the scenario then becomes #1 — no surrounding tx, routes to
replica) or accept that read-only transactions land on primary.

Audit of consumer services: LOS has 1 such site, onboarding has 0,
credential-manager TBD. Per-site fix is cheap.

### 6. `@Transactional` write tx with read-then-write → **PRIMARY** (both)

What it probes: read a row, modify it, write it back, all inside one
write transaction. Must be all-primary or the in-tx read won't see the
just-written data.

How the hybrid achieves it: `transactionalReadQueryStrategy: PRIMARY`
pins both the read and the write to `write_ds`. Read sees its own
in-progress changes.

### 7. `@Lock(PESSIMISTIC_WRITE)` SELECT inside `@Transactional` → **PRIMARY**

What it probes: Hibernate emits `SELECT … FOR UPDATE`. Must route to
primary regardless of whether anyone marked the tx as write.

How the hybrid achieves it: ShardingSphere's SQL parser explicitly
recognizes `FOR UPDATE` and routes write-only — works even outside a
surrounding tx (defense in depth).

### 8. Read within sticky window after a committed write → **PRIMARY**

What it probes: read-your-own-write semantics. After a commit,
subsequent reads briefly land on primary so they see the just-written
data despite replica lag.

How the hybrid achieves it: the write goes through
`StickyWriteRecorderAspect`, which updates
`StickyWriteContext.lastWriteTime`. The subsequent SELECT enters
`HintManagerBridgeAspect`; it computes
`(now − lastWriteTime) ≤ windowMs` → opens a HintManager pinned to
write_ds → ShardingSphere routes accordingly. Hint closes on aspect
exit.

This is the test that proves the bridge+recorder pair actually do what
the library's annotations claim to do.

### 9. Read after sticky window expires → **REPLICA**

What it probes: the inverse of scenario 8. After `window-ms` passes,
reads should flow back to replica.

How the hybrid achieves it: the bridge aspect's window check fails →
no hint is emitted → ShardingSphere routes the SELECT to read_ds via
load balancer normally. The test sleeps 5500 ms (> 5000 ms window) to
cross the boundary cleanly.

### 10. Native `@Query("SELECT … JOIN …")` outside any user tx → **REPLICA**

What it probes: native SQL bypasses Hibernate's JPQL parser. Routing
must still recognize it as a read.

How the hybrid achieves it: ShardingSphere parses native SELECT the same
way it parses JPQL-derived SELECT. The bridge aspect doesn't care
about query shape either — it makes decisions based on library
ThreadLocal state, which here is empty.

### 11. Native `@Modifying @Query("INSERT …")` outside user tx → **PRIMARY**

What it probes: native write SQL.

How the hybrid achieves it: SQL parser recognizes INSERT regardless of
JPQL vs native source. The side-channel JdbcTemplate confirms the row
on primary, not replica.

### 12. `@ForceMasterRead` annotation → **PRIMARY**

What it probes: explicit "this read should hit primary regardless"
intent expressed via the library's annotation.

How the hybrid achieves it: library's `ForceMasterReadAspect` runs the
outer `@Around` advice, sets `RoutingContextHolder.FORCE_MASTER = true`.
Method body calls `repo.findByEmail(...)`. Bridge aspect's `@Around`
fires on the repo call, reads `isForceMaster()` → true → opens
HintManager. After the repo call, hint closes. After the method
returns, library aspect clears FORCE_MASTER.

The call-site code is identical to what consumer services write today.
The annotation works end-to-end.

### 13. `@StickyRead(value = N)` annotation → **PRIMARY**

What it probes: widening the sticky window for a specific method.

How the hybrid achieves it: library's `StickyReadAspect` sets
`StickyReadContext.overrideWindowMs = N`. Test does a prep write so
`lastWriteTime` is populated. Method body calls `repo.findByEmail(...)`.
Bridge aspect computes the effective window from
`StickyReadContext.getOverrideWindowMs()` (overrides the default),
checks `(now − lastWriteTime) ≤ N` → opens HintManager → primary.

### 14. `@Async` method reading after parent thread wrote → **PRIMARY**

What it probes: cross-thread propagation. The parent writes,
schedules an async task, returns. The async task reads from a different
thread and must still see the just-written data.

Why it's load-bearing: LOS has 29 Kafka consumers; many service flows
write then dispatch to async processors. Read-your-own-write across
threads is a real production requirement.

How the hybrid achieves it: `StickyWriteContext.lastWriteTime` is a
JVM-static `volatile long` (the library's existing design). It
propagates trivially across threads. The async thread enters the bridge
aspect on its own repo call; ThreadLocal state (FORCE_MASTER,
StickyRead override) doesn't propagate, but the static field is shared.
The bridge sees a recent `lastWriteTime` and pins to primary.

This works without any application code changes — the hybrid's
mechanism inherits the library's JVM-global timestamp design.

### 15. Plain method call simulating a Kafka consumer write → **PRIMARY**

What it probes: a write code path with no Spring `@Transactional`, no
HTTP request context, no thread-local user context. Just a method that
updates a row.

How the hybrid achieves it: same path as scenario 3. SQL parser sees
UPDATE → primary. `StickyWriteRecorderAspect` registers an `afterCommit`
hook if there's a Spring tx, or invokes `markWrite()` immediately
otherwise. Either way the write lands on primary and the sticky window
is refreshed for any subsequent reads.

---

## Safety-net test

In addition to the 15 scenarios, the test class includes one more
assertion: open a direct connection to the replica container as
`readonly_app` and try to UPDATE. The test passes if and only if the
UPDATE throws `permission denied for table users`.

If this assertion ever passed silently, every write-routing test above
would be unreliable (a misrouted write could succeed on the replica
without detection). The safety net proves the read-only-role barrier
actually works.

---

## How to reproduce

```
cd /Users/dwivedi_utkarsh/Vegapay/read-write-routing-poc
mvn clean test
```

Expected: `Tests run: 15, Failures: 0, Errors: 0`. Run time ~1–2 min
on first invocation (Docker image pull), ~30 s on subsequent runs.

To capture results:

```
mvn clean test 2>&1 | tee test-output.log
```
