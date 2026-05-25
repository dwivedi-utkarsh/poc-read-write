# Testing — read-write-routing-poc

The 30-scenario integration matrix validating the productionized
`read-write-seperation-library` 3.0.0+. Each scenario probes a specific
behavior a real consumer service is likely to hit, asserts the expected
routing outcome, and is designed so that a regression in the library or
in ShardingSphere will fail the assertion loudly.

```
mvn test → Tests run: 30, Failures: 0, Errors: 0
```

## How routing is detected

Two real Postgres containers (Testcontainers), identical schema, different
marker data:

| | Primary container | Replica container |
|---|---|---|
| Connection user | `app` (owner, full privileges) | `readonly_app` (`GRANT SELECT` only) |
| `served_by` marker | `'PRIMARY'` for every row | `'REPLICA'` for every row |
| Receives writes | yes | misrouted writes throw `permission denied` |

Reads return their marker column. Writes are verified via a side-channel
`JdbcTemplate` that talks directly to each container — if a write was
routed to the replica, the read-only role rejects it with a loud
permission error. There is no silent stale-read or silent-write failure
mode.

`@BeforeEach` resets alice's email on primary and clears every
ThreadLocal / sticky-window flag (`RoutingContext.resetAll()` via
reflection on the library's `StickyWriteContext.lastWriteTime`) so each
scenario starts from a known baseline.

## Quick navigation

| Group | Scenarios | Theme |
|---|---|---|
| A. Basic read routing | 1, 2, 10 | Plain SELECTs flow to replica |
| B. Basic write routing | 3, 4, 11, 19, 20 | DML flows to primary |
| C. Transactional behavior | 5, 6, 7, 22, 30 | Open JDBC tx pins reads to primary |
| D. Force-master annotation | 12, 27 | `@ForceMasterRead` overrides routing |
| E. Sticky-window mechanism | 8, 9, 13, 14, 26, 28, 29 | Read-your-own-write outside tx |
| F. Inherited Spring Data methods | 16, 17, 18 | Wrapped in `@Transactional(readOnly=true)` |
| G. Edge cases & robustness | 15, 21, 23, 24, 25 | Rollback, exceptions, manual hints |

---

## Group A — Basic read routing

### 1. `findByEmail` outside any user transaction → **REPLICA**

**Probes:** the simplest plausible read. A derived finder, no `@Transactional` declared anywhere by application code.

**Pass condition:** ShardingSphere's SQL parser sees a `SELECT` outside any open JDBC tx → load balancer routes to `read_ds`. The bridge aspect runs but no library ThreadLocal is set, so no hint is emitted.

**Regression this catches:** load balancer not configured, replica DataSource not registered, bridge aspect spuriously emitting hints when none are warranted.

### 2. JPQL `@Query` outside any user transaction → **REPLICA**

**Probes:** same as scenario 1 but with a hand-written JPQL string instead of a derived finder. Many consumer services use hand-written JPQL for joins or named queries.

**Pass condition:** query shape doesn't matter to ShardingSphere — its parser identifies SELECT regardless of source. Bridge aspect treats `@Query`-derived calls identically to derived finders.

**Regression this catches:** routing logic accidentally depending on the source of the SQL string rather than the SQL itself.

### 10. Native `@Query("SELECT … JOIN …")` outside any user tx → **REPLICA**

**Probes:** native SQL bypasses Hibernate's JPQL parser entirely. Routing must still recognize it as a read.

**Pass condition:** ShardingSphere parses native SELECT the same way it parses JPQL-translated SELECT. The bridge aspect doesn't care about query shape either — it decides based on RoutingContext state, which here is empty.

**Regression this catches:** routing path that treats native SQL differently from JPQL SQL (a real risk if the routing logic were implemented at the Hibernate layer instead of the JDBC layer).

---

## Group B — Basic write routing

### 3. `@Modifying` UPDATE outside any user tx → **PRIMARY**

**Probes:** a Spring Data write via `@Modifying`. The repo method has its own `@Transactional` (Spring Data requires it on `@Modifying`), but the caller doesn't.

**Pass condition:** SQL parser sees UPDATE → routes to `write_ds`. The `StickyWriteRecorderAspect` registers an `afterCommit` hook on the repo's brief tx, refreshing `lastWriteTime` once the UPDATE commits.

**Regression this catches:** DML misrouting, or recorder failing to fire on `@Modifying` calls.

### 4. `save()` outside any user tx → **PRIMARY**

**Probes:** standard JPA persist via `JpaRepository.save()`. The side-channel `JdbcTemplate` against primary confirms the row landed.

**Pass condition:** Hibernate emits INSERT (or UPDATE for managed entities) → SQL parser → `write_ds`. The aspect's pointcut (`save*`) catches the call and registers the sticky-write hook.

**Regression this catches:** `save()` not matched by the recorder's pointcut, or DML misrouting.

### 11. Native `@Modifying @Query("INSERT …")` outside user tx → **PRIMARY**

**Probes:** native write SQL. The side-channel JdbcTemplate against primary confirms the row.

**Pass condition:** SQL parser recognizes INSERT regardless of JPQL-vs-native source.

**Regression this catches:** native writes accidentally routed to the read-only replica role (which would throw `permission denied`).

### 19. `saveAll(batch)` outside tx → **PRIMARY** (every row) + sticky refresh

**Probes:** batch inserts. Each row is verified individually on primary; the absence of any row on replica is also asserted. A subsequent read goes to primary, proving sticky-window was refreshed after the batch.

**Pass condition:** every row in the batch routes via the same path as scenario 4; recorder fires on the batched save.

**Regression this catches:** batch operations bypassing the routing layer, or recorder firing only once for the whole batch (sticky still works because we only need lastWriteTime updated once, but losing per-statement coverage would be a regression).

### 20. `deleteById()` outside tx → **PRIMARY** + sticky refresh

**Probes:** DELETE statement and recorder coverage of the delete path.

**Pass condition:** DELETE → `write_ds` via SQL parser; recorder's pointcut matches `delete*`; sticky-window refreshed.

**Regression this catches:** delete operations not refreshing sticky-window (would cause stale reads after deletions).

---

## Group C — Transactional behavior

### 5. `@Transactional(readOnly = true)` containing a SELECT → **PRIMARY** ⚠️

**Probes:** the canonical "offload to replica" pattern. This is the headline trade-off.

**Pass condition (the trade-off):** ShardingSphere is configured with `transactionalReadQueryStrategy: PRIMARY`, which pins every read inside any open JDBC tx to `write_ds`. This is a deliberate choice — it makes scenarios 6 and 7 safe by guaranteeing in-tx reads see just-written data inside the same tx.

**Why we accept it:** flipping to `transactionalReadQueryStrategy: DYNAMIC` would break scenarios 6/7 — much worse failure mode. Per-call-site fix is to drop the `@Transactional(readOnly=true)` annotation, which makes the SELECT autocommit and route to replica (becomes scenario 1).

**Regression this catches:** a future config drift to `DYNAMIC` would flip this PRIMARY → REPLICA. We want the test to fail loudly if that happens, because it signals the safety contract has changed.

### 6. `@Transactional` write tx with read-then-write → **PRIMARY** (both)

**Probes:** read a row, modify it, write it back, all inside one write transaction. Must be all-primary or the in-tx read won't see the just-written data.

**Pass condition:** `transactionalReadQueryStrategy: PRIMARY` pins both the read and the write to `write_ds`. Read sees its own in-progress changes.

**Regression this catches:** any change that lets in-tx reads go to replica — would silently break read-then-write logic in production code.

### 7. `@Lock(PESSIMISTIC_WRITE)` SELECT inside `@Transactional` → **PRIMARY**

**Probes:** Hibernate emits `SELECT … FOR UPDATE`. Must route to primary regardless of whether anyone marked the tx as write.

**Pass condition:** ShardingSphere's SQL parser explicitly recognizes `FOR UPDATE` and routes write-only — works even outside a wrapping tx (defense in depth).

**Regression this catches:** lock-clause SELECTs misrouted to replica, which would fail (replica is read-only) or silently take a useless replica lock.

### 22. `@Transactional(REQUIRES_NEW)` inner tx → **PRIMARY**

**Probes:** outer `@Transactional` + inner `REQUIRES_NEW` on a different bean opens a fresh JDBC tx. Reads inside should still be pinned.

**Pass condition:** ShardingSphere doesn't care which tx is active — any open JDBC tx triggers the `transactionalReadQueryStrategy=PRIMARY` rule.

**Regression this catches:** tx-pinning logic that incorrectly tracks only the outermost transaction, missing reads inside REQUIRES_NEW.

### 30. `TransactionTemplate.execute()` → reads inside → **PRIMARY**

**Probes:** legacy code that uses programmatic transactions instead of `@Transactional`.

**Pass condition:** the library and ShardingSphere don't care about the annotation — they react to the underlying JDBC tx state. Programmatic tx works identically.

**Regression this catches:** any tx-pinning logic that hooked specifically into Spring's `@Transactional` annotation processing instead of the lower-level tx infrastructure.

---

## Group D — Force-master annotation

### 12. `@ForceMasterRead` annotation → **PRIMARY**

**Probes:** explicit "this read should hit primary regardless" intent expressed via the library's annotation.

**Pass condition:** `ForceMasterReadAspect`'s `@Around` advice sets `RoutingContextHolder.FORCE_MASTER = true`. Bridge aspect fires on the repo call, reads `isForceMaster()` → true, opens `HintManager.setWriteRouteOnly()`. After the repo call, hint closes. After the outer method returns, the ThreadLocal is cleared.

**Regression this catches:** annotation aspect not firing, ThreadLocal not propagated to the bridge, or HintManager not honored by ShardingSphere.

### 27. Class-level `@ForceMasterRead` → **PRIMARY**

**Probes:** the `@within` clause of the aspect's pointcut. A class annotated `@ForceMasterRead` should pin reads in *all* its methods.

**Pass condition:** the aspect's pointcut is `@annotation(...) || @within(...)` — both method-level and class-level placement trigger the same advice.

**Regression this catches:** dropping the `@within` clause (would silently break class-level annotation usage, a real production pattern for repository facades).

---

## Group E — Sticky-window mechanism

### 8. Read within sticky window after a committed write → **PRIMARY**

**Probes:** read-your-own-write semantics. After a commit, subsequent reads briefly land on primary to ride out replication lag.

**Pass condition:** `StickyWriteRecorderAspect` updates `RoutingContext.lastWriteTime` on commit (afterCommit hook). The subsequent SELECT enters `HintManagerBridgeAspect`; `(now − lastWriteTime) ≤ windowMs` → opens a HintManager pinned to `write_ds`. Hint closes on aspect exit.

**Regression this catches:** the bridge+recorder pair failing to deliver the read-your-own-write guarantee. Critical correctness test.

### 9. Read after sticky window expires → **REPLICA**

**Probes:** the inverse of scenario 8. After `window-ms` passes, reads should flow back to replica.

**Pass condition:** the bridge's window check fails → no hint is emitted → SELECT routes via load balancer to `read_ds` normally. The test sleeps 5500ms (> 5000ms window) to cross the boundary cleanly.

**Regression this catches:** sticky-window failing to expire (would permanently pin reads to primary, defeating the read/write split).

### 13. `@StickyRead(value = N)` annotation → **PRIMARY**

**Probes:** widening the sticky window for a specific method.

**Pass condition:** `StickyReadAspect` sets `StickyReadContext.overrideWindowMs = N`. Test does a prep write so `lastWriteTime` is populated. Bridge computes the effective window from `getOverrideWindowMs()` (overrides the property default), checks `(now − lastWriteTime) ≤ N` → opens HintManager → primary.

**Regression this catches:** override-precedence inversion (property default winning over annotation override) or override-not-applied-at-all.

### 14. `@Async` method reading after parent thread wrote → **PRIMARY**

**Probes:** cross-thread propagation. The parent writes, schedules an async task, returns. The async task reads from a different thread and must still see the just-written data.

**Pass condition:** `StickyWriteContext.lastWriteTime` is a `static volatile long` — JVM-wide, not ThreadLocal. The async thread enters the bridge aspect on its own repo call; ThreadLocal state (forceMaster, stickyReadOverride) does NOT propagate, but the static field is shared. The bridge sees a recent `lastWriteTime` and pins to primary.

**Regression this catches:** anyone "fixing" `lastWriteTime` to be ThreadLocal (would silently break read-your-own-write for any async/Kafka flow).

### 26. Sticky window refresh — second write extends window

**Probes:** the refresh-on-newer-write semantics. Otherwise a long-running session of continuous writes would expire the window between writes.

**Pass condition:** lastWriteTime is overwritten by each write — a second write at t=3s means the window is now `[3, 8]` instead of `[0, 5]`. A read at t=5.5s is past the first write's expiry but inside the second write's window → PRIMARY. A read at t=8.5s is past both → REPLICA.

**Regression this catches:** lastWriteTime accidentally being set-once instead of refreshable.

### 28. `@StickyRead(0)` falls back to property default → **PRIMARY**

**Probes:** annotation with no value should use the global sticky window from properties.

**Pass condition:** in `HintManagerBridgeAspect.effectiveWindow()`, when `getOverrideWindowMs() == 0`, the code falls through to `stickyProps.getWindowMs()`. With a recent write, the default 5000ms window applies.

**Regression this catches:** an override-precedence change that treats `0` as a real override (would disable sticky window for `@StickyRead`-annotated methods).

### 29. Cross-thread sticky via raw `new Thread()` → **PRIMARY**

**Probes:** the JVM-global design isn't tied to Spring's `@Async` machinery. Raw threads (or any other executor) should also see the sticky timestamp.

**Pass condition:** identical to scenario 14 — `lastWriteTime` is JVM-static; any thread that enters the bridge aspect reads the same value.

**Regression this catches:** any change that makes sticky-write propagation depend on a thread-context-propagation mechanism (TaskDecorator, MDC, etc.) — would silently break for raw threads.

---

## Group F — Inherited Spring Data methods (the trade-off zone)

`JpaRepository` defines methods like `count`, `existsById`, `findAll`, `findAll(Pageable)`, `deleteById`, etc. These are implemented by `SimpleJpaRepository`, which has a class-level `@Transactional(readOnly=true)` annotation. So Spring Data wraps every inherited method invocation in a read-only transaction. Combined with `transactionalReadQueryStrategy=PRIMARY`, these methods always route to primary — the same trade-off as scenario 5.

User-defined `@Query` methods and derived finders (like `findByEmail`) do **not** get this wrapper, because they're implemented via Spring Data's query-method machinery rather than `SimpleJpaRepository`. That's why scenarios 1, 2, 10 route correctly to replica.

### 16. `count()` routes to **PRIMARY**

**Probes:** the inherited `count()`. Detection trick: insert a throwaway row directly on primary, count before vs after — if `count()` reads from primary, it sees the row.

**Pass condition:** Spring Data's class-level `@Transactional(readOnly=true)` opens a JDBC tx; `transactionalReadQueryStrategy=PRIMARY` pins the read.

**Regression this catches:** a change in either Spring Data's tx wrapping behavior or ShardingSphere's tx-pinning rule.

### 17. `existsById()` routes to **PRIMARY**

**Probes:** the inherited `existsById`. Detection: a row that exists only on primary; if exists() returns true, the read went to primary.

**Pass condition:** same as 16.

**Regression this catches:** same.

### 18. `findAll(Pageable)` routes to **PRIMARY** (both queries)

**Probes:** paging queries emit TWO statements — a SELECT for the page and a `count(*)` for total pages. Both should land on primary by the same wrapping-tx mechanism.

**Pass condition:** both queries run inside the wrapping read-only tx; both pinned.

**Regression this catches:** the count query somehow escaping the wrapping tx (would yield a stale total-count alongside a fresh page, mathematical inconsistency).

---

## Group G — Edge cases & robustness

### 15. Kafka-style write (no tx, no HTTP) → **PRIMARY**

**Probes:** a write code path with no Spring `@Transactional`, no HTTP request context, no thread-local user context. Just a method that updates a row.

**Pass condition:** same as scenario 3. SQL parser sees UPDATE → primary. Recorder registers an `afterCommit` hook if there's a Spring tx, or invokes `markWrite()` immediately otherwise.

**Regression this catches:** routing dependent on Spring web/MVC context (would break Kafka consumers, schedulers, CLI tools).

### 21. Rolled-back tx with write inside → next read → **REPLICA**

**Critical correctness test.** A write happens inside `@Transactional`, then a `RuntimeException` rolls back the tx. The `afterCommit` synchronization MUST NOT fire — so `StickyWriteContext.lastWriteTime` MUST NOT be refreshed. A read immediately after goes to replica because no sticky-write was recorded.

**Pass condition:** Spring's `TransactionSynchronization.afterCommit` is only called on successful commit, never on rollback. The recorder hooks afterCommit specifically (not `afterCompletion`).

**Regression this catches:** anyone "simplifying" the recorder to use `afterCompletion` (which fires on both commit and rollback) — would falsely pin reads after rolled-back writes, masking real failures and burning replica capacity.

### 23. Hibernate dirty-check UPDATE → routes **PRIMARY** + sticky NOT refreshed (documented gap)

**Probes:** fetch an entity inside `@Transactional`, mutate a field, exit without calling `save()`. Hibernate emits UPDATE on flush via dirty checking.

**Pass condition (routing):** ShardingSphere's SQL parser still sees UPDATE → primary. The UPDATE lands correctly.

**Known gap (sticky):** the recorder's pointcut is `save* / delete* / @Modifying`. Dirty-check flush isn't triggered by any of those — it's triggered by Hibernate's persistence-context lifecycle. So `lastWriteTime` is NOT refreshed. A subsequent read goes to replica even though a write just happened.

**Test asserts both behaviors** — the UPDATE lands on primary (proves routing) AND a subsequent read goes to replica (documents the gap). If the gap is ever closed (e.g., by hooking Hibernate event listeners), this test will fail loudly and the doc should be updated.

**Workaround for production code:** prefer explicit `save()` (or `saveAndFlush()`) for any code that needs read-your-own-write coverage. Dirty-check-only updates are fine for routing but not for sticky-window.

### 24. Application code holds its own HintManager → **PRIMARY**, no fight

**Probes:** a call site that opens `HintManager.setWriteRouteOnly()` manually before calling a repo method.

**Pass condition:** the bridge aspect's `getInstance()` throws `IllegalStateException` (one already open on this thread). The bridge catches that exception and just calls `proceed()` — trusting the caller's hint, not fighting them.

**Regression this catches:** anyone changing the bridge to use force-replace semantics (would corrupt application-side fine-grained routing intent).

### 25. `@ForceMasterRead` method throws → next read → **REPLICA** (no hint leak)

**Critical robustness test.** A repo call inside `@ForceMasterRead` throws (e.g., `Optional.orElseThrow`). The bridge's `finally{}` must close the HintManager and the outer aspect's `finally{}` must clear the ThreadLocal. The next read on the same pooled thread must not be pinned.

**Pass condition:** both aspects use `try-finally` (not `try-catch-rethrow`), so cleanup happens regardless of exceptional exit.

**Regression this catches:** the highest-severity bug category in HintManager usage — if cleanup ever leaks, pooled web threads bleed force-master state into completely unrelated requests, silently pinning every read on that thread to primary until JVM restart.

---

## Sticky-window scope contract — what these tests do NOT cover

The 30-scenario matrix exercises the library on a single JVM. The known limitations of the sticky-window mechanism are out of scope here and need to be addressed separately:

- **Cross-pod / multi-JVM deployments.** `lastWriteTime` is a `static volatile long`, scoped to one classloader. A write on pod A does not refresh pod B's sticky window. Tests 8, 14, 26, 29 prove it works *within* a JVM; they do not prove anything about a distributed deployment.
- **Cross-entity bleed.** Any write refreshes the global timestamp, so unrelated reads from any code path get pinned for the window's duration. Under high write rates, the replica receives effectively no traffic.
- **Replication-lag-as-a-guess.** The window is a fixed timer. If actual lag exceeds `window-ms`, reads are still routed to replica and may be stale.

Productionizing the sticky-window mechanism (Redis-backed, per-entity keying, or LSN-based correctness) is tracked separately. The tests in this matrix are correct for the current JVM-local design.

## Running

```
cd /Users/dwivedi_utkarsh/Vegapay/read-write-routing-poc
mvn clean test
```

Requires Docker. First run pulls `postgres:14` (~1–2 min); subsequent runs take ~30s. Scenario 26 alone takes ~8s (sticky-window timing).

To capture results:

```
mvn clean test 2>&1 | tee test-output.log
```

Expected: `Tests run: 30, Failures: 0, Errors: 0, Skipped: 0`.
