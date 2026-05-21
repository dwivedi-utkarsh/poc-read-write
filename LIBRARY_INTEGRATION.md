# Library Integration — what changes in `read-write-seperation-library`

This document captures what needs to change in
`/Users/dwivedi_utkarsh/Vegapay/vegapay-library/read-write-seperation-library/`
to productionize the hybrid the POC proves out. Read this when the team is
ready to do the library refactor.

The principle:
- **Keep the library's surface** (annotations, properties, ThreadLocals) so
  consumer services don't change.
- **Swap the engine underneath** — replace the in-house `RoutingDataSource`
  with a `ShardingSphereDataSource` built from the same property inputs.
- **Add two small aspects** that translate between the library's
  ThreadLocal-based intent and ShardingSphere's HintManager mechanism.

---

## What stays untouched

These files are correct as-is. Do not modify:

```
annotation/ForceMasterRead.java
annotation/StickyRead.java
aspect/ForceMasterReadAspect.java               (sets RoutingContextHolder.FORCE_MASTER)
aspect/StickyReadAspect.java                    (sets StickyReadContext.overrideWindowMs)
context/RoutingContextHolder.java
context/stickyReadWriteContext/StickyWriteContext.java
context/stickyReadWriteContext/StickyReadContext.java
context/dataSourceContext/DataSourceContextHolder.java
config/properties/WriteDataSourceProperties.java
config/properties/ReadDataSourceProperties.java
config/properties/StickyWriteProperties.java
config/DataSourceConfig.java                    (still builds 2 Hikari pools — fine)
```

The library's annotation API and ThreadLocal intent-tracking are not the
problem. The engine that consumes them is.

---

## What gets added (two new files)

### 1. `aspect/HintManagerBridgeAspect.java`

What it does: at every Spring Data repository method call, peek at the
library's ThreadLocals. If they say "pin to primary," tell ShardingSphere
via `HintManager.setWriteRouteOnly()` for the duration of that call.

```java
@Aspect
@Order(Integer.MAX_VALUE)
public class HintManagerBridgeAspect {

    private final StickyWriteProperties stickyProps;

    public HintManagerBridgeAspect(StickyWriteProperties stickyProps) {
        this.stickyProps = stickyProps;
    }

    @Around("execution(* org.springframework.data.repository.Repository+.*(..))")
    public Object bridge(ProceedingJoinPoint pjp) throws Throwable {
        if (!shouldPinToPrimary()) {
            return pjp.proceed();
        }
        HintManager hint;
        try { hint = HintManager.getInstance(); }
        catch (IllegalStateException alreadyHeld) { return pjp.proceed(); }
        try {
            hint.setWriteRouteOnly();
            return pjp.proceed();
        } finally {
            hint.close();
        }
    }

    private boolean shouldPinToPrimary() {
        if (RoutingContextHolder.isForceMaster()) return true;
        long window = effectiveWindow();
        if (window <= 0) return false;
        long lastWrite = StickyWriteContext.getLastWriteTime();
        if (lastWrite <= 0) return false;
        return (System.currentTimeMillis() - lastWrite) <= window;
    }

    private long effectiveWindow() {
        long override = StickyReadContext.getOverrideWindowMs();
        if (override > 0) return override;
        return (stickyProps != null && stickyProps.isEnabled())
                ? stickyProps.getWindowMs() : 0;
    }
}
```

Plain-English logic — three questions, in order:
1. Has someone called `@ForceMasterRead`? → pin to primary.
2. Is there a recent write and we're inside the sticky window? → pin to primary.
3. Otherwise → do nothing; let ShardingSphere's SQL parser route normally.

The `try-finally` around `HintManager` keeps the hint scoped to a single repo
call — it cannot leak to other calls on the same thread.

### 2. `aspect/StickyWriteRecorderAspect.java`

What it does: after every repository write completes, stamp the JVM-global
`lastWriteTime` so the bridge aspect has something to compare against on
subsequent reads.

```java
@Aspect
public class StickyWriteRecorderAspect {

    @AfterReturning(
        "execution(* org.springframework.data.repository.CrudRepository+.save*(..)) " +
        "|| execution(* org.springframework.data.repository.CrudRepository+.delete*(..)) " +
        "|| @annotation(org.springframework.data.jpa.repository.Modifying)"
    )
    public void recordWrite(JoinPoint jp) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override public void afterCommit() { StickyWriteContext.markWrite(); }
                });
        } else {
            StickyWriteContext.markWrite();
        }
    }
}
```

Plain-English logic — after any `save*` / `delete*` / `@Modifying` returns:
- Inside a Spring transaction → wait until commit, *then* stamp the time
  (don't stamp prematurely if the tx might roll back).
- Outside a transaction → JDBC autocommit already happened, stamp now.

This is what the library currently *tries* to do via
`RoutingDataSource.registerWriteSynchronizationIfNeeded()`, but that code
never executes because of the eager-connection timing bug. This replaces it.

---

## What gets replaced

### `config/DataSourceRoutingConfig.java` — the body

Today this file builds an `@Primary` `RoutingDataSource` (the broken
`AbstractRoutingDataSource` subclass). Replace the body so it builds a
`ShardingSphereDataSource` from the same two property blocks the library
already reads, and registers the two new aspects.

**Before (current — broken):**

```java
@Primary @Bean(name = "customRoutingDataSource")
public DataSource customRoutingDataSource(
        @Qualifier("writeDataSource") DataSource writeDataSource,
        @Qualifier("readDataSource") DataSource readDataSource) {

    RoutingDataSource routingDataSource = new RoutingDataSource(stickyWriteProperties);
    Map<Object, Object> dataSourceMap = new HashMap<>();
    dataSourceMap.put(DataSourceType.WRITE, writeDataSource);
    if (readDataSource != null) {
        dataSourceMap.put(DataSourceType.READ, readDataSource);
    }
    routingDataSource.setTargetDataSources(dataSourceMap);
    routingDataSource.setDefaultTargetDataSource(writeDataSource);
    return routingDataSource;
}
```

**After (hybrid):**

```java
@Primary @Bean(name = "customRoutingDataSource")
public DataSource customRoutingDataSource(WriteDataSourceProperties writeProps,
                                          ReadDataSourceProperties readProps) throws Exception {
    String yaml = buildShardingSphereYaml(writeProps, readProps);
    return YamlShardingSphereDataSourceFactory.createDataSource(
            yaml.getBytes(StandardCharsets.UTF_8));
}

@Bean
public HintManagerBridgeAspect hintManagerBridgeAspect(StickyWriteProperties p) {
    return new HintManagerBridgeAspect(p);
}

@Bean
public StickyWriteRecorderAspect stickyWriteRecorderAspect() {
    return new StickyWriteRecorderAspect();
}

private String buildShardingSphereYaml(WriteDataSourceProperties w,
                                       ReadDataSourceProperties r) {
    return "mode: { type: Standalone }\n" +
           "dataSources:\n" +
           "  write_ds:\n" +
           "    dataSourceClassName: com.zaxxer.hikari.HikariDataSource\n" +
           "    driverClassName: " + w.getDriverClassName() + "\n" +
           "    jdbcUrl: "          + w.getUrl()             + "\n" +
           "    username: "         + w.getUsername()        + "\n" +
           "    password: "         + w.getPassword()        + "\n" +
           "  read_ds:\n" +
           "    dataSourceClassName: com.zaxxer.hikari.HikariDataSource\n" +
           "    driverClassName: " + r.getDriverClassName() + "\n" +
           "    jdbcUrl: "          + r.getUrl()             + "\n" +
           "    username: "         + r.getUsername()        + "\n" +
           "    password: "         + r.getPassword()        + "\n" +
           "rules:\n" +
           "  - !READWRITE_SPLITTING\n" +
           "    dataSources:\n" +
           "      routing_ds:\n" +
           "        writeDataSourceName: write_ds\n" +
           "        readDataSourceNames: [read_ds]\n" +
           "        transactionalReadQueryStrategy: PRIMARY\n" +
           "        loadBalancerName: round_robin\n" +
           "    loadBalancers:\n" +
           "      round_robin: { type: ROUND_ROBIN }\n" +
           "  - !SINGLE\n" +
           "    tables: [\"*.*\"]\n" +
           "props:\n" +
           "  sql-show: false\n";
}
```

Plain-English logic: instead of returning a custom `AbstractRoutingDataSource`,
return a `ShardingSphereDataSource` built from the same property inputs.
Consumer services don't know the difference at the property level — they
still configure `spring.datasource.write.*` and `spring.datasource.read.*`
exactly as before.

The two aspect beans get registered alongside the DataSource, so they
activate automatically as soon as `spring.datasource.routing.enabled=true`.

Notes on the YAML choices:
- `transactionalReadQueryStrategy: PRIMARY` pins reads inside any JDBC tx to
  the write DS. This is what makes read-then-write inside a `@Transactional`
  safe (the read sees the just-pending write). Trade-off:
  `@Transactional(readOnly=true)` SELECTs also route to primary; consumer
  services with replica-offload-by-readOnly-tx need to drop the surrounding
  tx at those call sites (rare in audited code).
- `!SINGLE rule with tables: ["*.*"]` is mandatory for pure read/write
  splitting (no sharding rules). Without it, ShardingSphere's schema
  builder doesn't register any tables and every query fails with
  `TableNotExistsException`.

---

## What gets deleted (or quarantined)

### `config/RoutingDataSource.java`

This is the broken `AbstractRoutingDataSource` subclass. Once
`DataSourceRoutingConfig` no longer wires it as `@Primary`, it's
unreachable. Two choices:

- **Delete outright** if you commit to the hybrid as the only path forward.
  Simpler, removes the temptation to leave both engines running.
- **Move to `legacy/` subpackage** and add a property switch like
  `spring.datasource.routing.engine=in-house|shardingsphere` if you want a
  per-service rollback path during the first release. Schedule a deletion
  date in `DEPRECATION.md` so it doesn't live forever (see "Deprecation
  path" in the POC README).

Recommended: delete on the first hybrid release. The in-house engine
doesn't actually work in production today (see the bug discussion in
the POC root README), so there's nothing to "preserve" that's worth
preserving.

---

## What gets added to `pom.xml`

```xml
<dependency>
    <groupId>org.apache.shardingsphere</groupId>
    <artifactId>shardingsphere-jdbc-core</artifactId>
    <version>5.4.1</version>
</dependency>
```

This is the entire new transitive surface added to the library. Consumer
services pick it up on the next library version bump. Footprint: ~25–30 MB
across all transitive JARs (SQL parsers, ANTLR runtime, Groovy for inline
expressions); cold-start cost ~500ms–1s for the parser + rule initialization.

---

## What the consumer service has to do

Nothing in the application code. The properties they already set continue
to drive everything:

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

After the library version bump, these same properties feed ShardingSphere
instead of the broken `RoutingDataSource`. `@ForceMasterRead`, `@StickyRead`,
and sticky-window read-your-own-write all keep working with the same code at
the call site. The replica DB starts actually receiving read traffic — for
the first time since the library was rolled out.

---

## Putting it in one paragraph

Add two small aspect classes (`HintManagerBridgeAspect`,
`StickyWriteRecorderAspect`). Replace the body of `DataSourceRoutingConfig`
so it builds a `ShardingSphereDataSource` from the existing property blocks
instead of the buggy `RoutingDataSource`. Delete (or quarantine)
`RoutingDataSource.java`. Add one dependency to `pom.xml`. That's it — the
library's annotation API, property surface, and ThreadLocal model all stay
exactly as they are; the engine underneath gets swapped for one that
actually works.

---

## Rollout sequence (suggested)

1. Make these changes in `read-write-seperation-library`. Bump version to
   `3.0.0-SNAPSHOT`.
2. Promote the POC's 15-scenario test into the library's own
   `src/test/java`. Every future library PR re-runs the matrix.
3. Pilot on the smallest consumer (credential-manager). Bump dep, deploy,
   watch metrics for a week — primary connection count should drop, replica
   should rise.
4. Roll out to onboarding, then LOS. Each is a dep bump only.
5. Once all three consumers are on the hybrid for a quarter without
   incident, decide whether to keep the property switch for future-proofing
   or remove it.
