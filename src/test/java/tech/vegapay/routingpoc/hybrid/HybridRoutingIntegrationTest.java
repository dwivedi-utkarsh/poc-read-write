package tech.vegapay.routingpoc.hybrid;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tech.vegapay.readwriteseperationlibrary.context.RoutingContextHolder;
import tech.vegapay.readwriteseperationlibrary.context.stickyReadWriteContext.StickyReadContext;
import tech.vegapay.readwriteseperationlibrary.context.stickyReadWriteContext.StickyWriteContext;
import tech.vegapay.routingpoc.RoutingPocApplication;
import tech.vegapay.routingpoc.User;
import tech.vegapay.routingpoc.UserRepo;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 15-scenario routing matrix validating the productionized
 * read-write-seperation-library 3.0.0+. The library auto-configures
 * itself via spring.factories: it reads spring.datasource.write/read.*
 * + spring.datasource.routing.enabled, builds the ShardingSphereDataSource
 * internally, and registers the four aspects
 * (ForceMasterRead, StickyRead, HintManagerBridge, StickyWriteRecorder).
 *
 * Compared to the old hybrid test, this class:
 *   - Imports nothing library-internal (no @Import of test configs)
 *   - Feeds connection details to the library via @DynamicPropertySource
 *   - Has no POC-side ShardingSphere wiring — the library owns it
 *
 * Mirrors the consumer-service integration contract exactly: add the dep,
 * set the properties, done.
 */
@SpringBootTest(classes = {
        RoutingPocApplication.class,
        HybridRoutingIntegrationTest.ContainersConfig.class
})
@ActiveProfiles("test")
@Testcontainers
class HybridRoutingIntegrationTest {

    @DynamicPropertySource
    static void wireDataSourceProperties(DynamicPropertyRegistry registry) {
        if (!primary.isRunning()) primary.start();
        if (!replica.isRunning()) replica.start();
        registry.add("spring.datasource.write.url", primary::getJdbcUrl);
        registry.add("spring.datasource.write.username", primary::getUsername);
        registry.add("spring.datasource.write.password", primary::getPassword);
        registry.add("spring.datasource.write.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.datasource.read.url", replica::getJdbcUrl);
        registry.add("spring.datasource.read.username", () -> "readonly_app");
        registry.add("spring.datasource.read.password", () -> "readonly");
        registry.add("spring.datasource.read.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.datasource.routing.enabled", () -> "true");
        registry.add("spring.datasource.routing.sticky-writes.enabled", () -> "true");
        registry.add("spring.datasource.routing.sticky-writes.window-ms", () -> "5000");
        // Scenario 31 — set distinct pool sizes per side so the plumbing test
        // can detect them on the underlying HikariDataSource instances.
        registry.add("spring.datasource.write.hikari.maximum-pool-size", () -> "7");
        registry.add("spring.datasource.write.hikari.minimum-idle", () -> "2");
        registry.add("spring.datasource.read.hikari.maximum-pool-size", () -> "11");
        registry.add("spring.datasource.read.hikari.minimum-idle", () -> "3");
    }

    private static final UUID ALICE = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String ALICE_EMAIL = "alice@example.com";

    // Bob is never mutated by any scenario, so reads against bob's email
    // succeed regardless of which DB serves them — the marker column tells us
    // which one did. Tests that want to combine a write (to alice) with a
    // read (for routing-detection purposes) use bob as the read target.
    private static final String BOB_EMAIL = "bob@example.com";

    @Container
    static PostgreSQLContainer<?> primary = new PostgreSQLContainer<>("postgres:14")
            .withDatabaseName("routingdb")
            .withUsername("app")
            .withPassword("app")
            .withInitScript("init/primary.sql");

    @Container
    static PostgreSQLContainer<?> replica = new PostgreSQLContainer<>("postgres:14")
            .withDatabaseName("routingdb")
            .withUsername("app")
            .withPassword("app")
            .withInitScript("init/replica.sql");

    @TestConfiguration
    static class ContainersConfig {

        @Bean
        PostgreSQLContainer<?> primary() {
            if (!primary.isRunning()) primary.start();
            return primary;
        }

        @Bean
        PostgreSQLContainer<?> replica() {
            if (!replica.isRunning()) replica.start();
            return replica;
        }

        @Bean("primaryJdbc")
        JdbcTemplate primaryJdbc() {
            return directJdbc(primary, primary.getUsername(), primary.getPassword());
        }

        @Bean("replicaJdbc")
        JdbcTemplate replicaJdbc() {
            return directJdbc(replica, replica.getUsername(), replica.getPassword());
        }

        private static JdbcTemplate directJdbc(PostgreSQLContainer<?> c, String user, String pw) {
            HikariDataSource ds = new HikariDataSource();
            ds.setJdbcUrl(c.getJdbcUrl());
            ds.setUsername(user);
            ds.setPassword(pw);
            ds.setDriverClassName("org.postgresql.Driver");
            ds.setMaximumPoolSize(2);
            return new JdbcTemplate(ds);
        }
    }

    @Autowired
    UserRepo repo;

    @Autowired
    HybridUserService svc;

    @Autowired
    ClassLevelForceMasterService classLevelForce;

    @Autowired
    @Qualifier("primaryJdbc")
    JdbcTemplate primaryJdbc;

    @Autowired
    @Qualifier("replicaJdbc")
    JdbcTemplate replicaJdbc;

    @Autowired
    javax.sql.DataSource routingDataSource;

    @BeforeEach
    void resetAliceAndRoutingState() throws Exception {
        primaryJdbc.update("UPDATE users SET email = ? WHERE id = ?", ALICE_EMAIL, ALICE);
        clearAllRoutingState();
    }

    @AfterEach
    void clearStateBetweenTests() throws Exception {
        clearAllRoutingState();
    }

    /**
     * The library's StickyReadAspect sets StickyReadContext.overrideWindowMs but
     * never clears it (a known library quirk), and StickyWriteContext.lastWriteTime
     * is a static JVM-global that bleeds across tests. Reset everything between
     * tests so each scenario starts from a known clean state.
     */
    private static void clearAllRoutingState() throws Exception {
        RoutingContextHolder.clearForceMaster();
        StickyReadContext.clear();
        Field lastWrite = StickyWriteContext.class.getDeclaredField("lastWriteTime");
        lastWrite.setAccessible(true);
        lastWrite.setLong(null, 0L);
        StickyWriteContext.clearRegistration();
    }

    // ────────────────────────────────────────────────────────────────────────
    // Scenario 1 — findByEmail outside tx → REPLICA (no force, no sticky)
    // ────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("1: findByEmail outside tx → REPLICA")
    void scenario01_findByEmail_outsideTx_routesToReplica() {
        assertThat(svc.plainRead(ALICE_EMAIL)).isEqualTo("REPLICA");
    }

    // ────────────────────────────────────────────────────────────────────────
    // Scenario 2 — JPQL @Query outside tx → REPLICA
    // ────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("2: JPQL @Query outside tx → REPLICA")
    void scenario02_jpqlQuery_outsideTx_routesToReplica() {
        assertThat(svc.jpqlRead(ALICE_EMAIL)).isEqualTo("REPLICA");
    }

    // ────────────────────────────────────────────────────────────────────────
    // Scenario 3 — @Modifying UPDATE outside tx → PRIMARY
    // ────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("3: @Modifying UPDATE outside tx → PRIMARY")
    void scenario03_modifyingUpdate_outsideTx_routesToPrimary() {
        String newEmail = "s3-" + UUID.randomUUID() + "@example.com";
        assertThatCode(() -> svc.plainWriteOutsideTx(ALICE, newEmail))
                .doesNotThrowAnyException();
        assertThat(countByEmail(primaryJdbc, newEmail)).isEqualTo(1L);
        assertThat(countByEmail(replicaJdbc, newEmail)).isEqualTo(0L);
    }

    // ────────────────────────────────────────────────────────────────────────
    // Scenario 4 — save() outside tx → PRIMARY
    // ────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("4: save() outside tx → PRIMARY")
    void scenario04_save_outsideTx_routesToPrimary() {
        UUID id = UUID.randomUUID();
        User u = new User();
        u.setId(id);
        u.setEmail("s4-" + id + "@example.com");
        u.setServedBy("PRIMARY");
        assertThatCode(() -> repo.saveAndFlush(u)).doesNotThrowAnyException();
        assertThat(countById(primaryJdbc, id)).isEqualTo(1L);
        assertThat(countById(replicaJdbc, id)).isEqualTo(0L);
    }

    // ────────────────────────────────────────────────────────────────────────
    // Scenario 5 — @Transactional(readOnly = true) → PRIMARY (UNCHANGED).
    //
    // The hybrid doesn't fix this. ShardingSphere pins every read inside a
    // JDBC tx to the write DS when transactionalReadQueryStrategy=PRIMARY, and
    // there is no HintManager call that means "force read DS for this query"
    // to counteract it. The library, when it owned the routing decision,
    // honored Spring's readOnly flag and routed to replica; under hybrid it
    // cannot do that without giving up the write-tx pinning behavior we want
    // for scenarios 6 and 7.
    //
    // If this scenario needs to flip, the choices are:
    //   - Change ShardingSphere to transactionalReadQueryStrategy=DYNAMIC
    //     (and accept the consequences on scenario 6/7);
    //   - Drop @Transactional(readOnly=true) at the call site and let the
    //     SELECT execute outside any tx (becomes the scenario 1 path).
    // ────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("5: @Transactional(readOnly=true) SELECT → PRIMARY (hybrid does NOT fix this)")
    void scenario05_readOnlyTx_underHybrid_stillRoutesToPrimary() {
        assertThat(svc.readInReadOnlyTx(ALICE_EMAIL)).isEqualTo("PRIMARY");
    }

    // ────────────────────────────────────────────────────────────────────────
    // Scenario 6 — write tx with read+write → both PRIMARY
    // ────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("6: write tx with read+write → both PRIMARY")
    void scenario06_writeTx_pinsBothToPrimary() {
        assertThat(svc.readThenWriteInTx(ALICE)).isEqualTo("PRIMARY");
    }

    // ────────────────────────────────────────────────────────────────────────
    // Scenario 7 — @Lock(PESSIMISTIC_WRITE) inside @Transactional → PRIMARY
    // ────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("7: @Lock(PESSIMISTIC_WRITE) in tx → PRIMARY")
    void scenario07_pessimisticLock_inTx_routesToPrimary() {
        assertThat(svc.lockAndUpdate(ALICE)).isEqualTo("PRIMARY");
    }

    // ────────────────────────────────────────────────────────────────────────
    // Scenario 8 — read within sticky window after a committed write outside tx
    //
    // Approach D: REPLICA (no sticky concept).
    // Hybrid:     PRIMARY — StickyWriteRecorderAspect marks the timestamp on
    //             UPDATE return, HintManagerBridgeAspect sees the recent write
    //             and pins the subsequent SELECT to write_ds.
    //
    // This is the headline fix the hybrid provides.
    // ────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("8: read after write within sticky window → PRIMARY (fixed by hybrid)")
    void scenario08_readAfterWriteOutsideTx_underHybrid_routesToPrimary() {
        // Write to alice, read bob. The write seeds lastWriteTime; the read
        // target is bob so the read succeeds regardless of which DB serves it.
        assertThat(svc.writeThenReadOutsideTx(ALICE, BOB_EMAIL)).isEqualTo("PRIMARY");
    }

    // ────────────────────────────────────────────────────────────────────────
    // Scenario 9 — read after sticky window expires → REPLICA
    // Window-ms is 5000; we sleep 5500 to cleanly cross the boundary.
    // ────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("9: read after sticky window expires → REPLICA")
    void scenario09_readAfterStickyExpires_routesToReplica() throws InterruptedException {
        svc.plainWriteOutsideTx(ALICE, "s9-" + UUID.randomUUID() + "@example.com");
        TimeUnit.MILLISECONDS.sleep(5500);
        assertThat(svc.plainRead(ALICE_EMAIL)).isEqualTo("REPLICA");
    }

    // ────────────────────────────────────────────────────────────────────────
    // Scenario 10 — native SELECT outside tx → REPLICA
    // ────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("10: native SELECT outside tx → REPLICA")
    void scenario10_nativeSelect_outsideTx_routesToReplica() {
        assertThat(svc.nativeRead(ALICE_EMAIL)).isEqualTo("REPLICA");
    }

    // ────────────────────────────────────────────────────────────────────────
    // Scenario 11 — native @Modifying INSERT outside tx → PRIMARY
    // ────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("11: native INSERT outside tx → PRIMARY")
    void scenario11_nativeInsert_outsideTx_routesToPrimary() {
        UUID id = UUID.randomUUID();
        String email = "s11-" + id + "@example.com";
        assertThatCode(() -> svc.nativeInsertOutsideTx(id, email))
                .doesNotThrowAnyException();
        assertThat(countById(primaryJdbc, id)).isEqualTo(1L);
        assertThat(countById(replicaJdbc, id)).isEqualTo(0L);
    }

    // ────────────────────────────────────────────────────────────────────────
    // Scenario 12 — @ForceMasterRead annotation → PRIMARY
    //
    // Note: under Approach D this scenario required the call site to use
    // HintManager directly. Under hybrid the annotation does the same job
    // transparently — call-site code is identical to library-native code.
    // ────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("12: @ForceMasterRead annotation → PRIMARY (no call-site changes)")
    void scenario12_forceMasterReadAnnotation_routesToPrimary() {
        assertThat(svc.forceMasterRead(ALICE_EMAIL)).isEqualTo("PRIMARY");
    }

    // ────────────────────────────────────────────────────────────────────────
    // Scenario 13 — @StickyRead(value=N) annotation widens the window.
    // The annotation alone doesn't pin to primary — it just widens the window
    // for THIS thread to N ms. A recent write must exist for sticking to
    // happen. Set one up explicitly, then call the annotated method.
    // ────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("13: @StickyRead annotation after recent write → PRIMARY")
    void scenario13_stickyReadAnnotation_afterWrite_routesToPrimary() {
        svc.plainWriteOutsideTx(ALICE, "s13-prep-" + UUID.randomUUID() + "@example.com");
        assertThat(svc.stickyReadWidened(BOB_EMAIL)).isEqualTo("PRIMARY");
    }

    // ────────────────────────────────────────────────────────────────────────
    // Scenario 14 — @Async read after parent thread wrote.
    //
    // Approach D: REPLICA (no sticky concept).
    // Hybrid:     PRIMARY. StickyWriteContext.lastWriteTime is a static
    //             volatile long — JVM-wide, not ThreadLocal. The async thread
    //             enters HintManagerBridgeAspect and sees the same lastWriteTime
    //             the parent thread set; sticky pins the SELECT to primary.
    //
    // This is a quiet second win for the hybrid — read-your-own-write
    // transparently extends to @Async / Kafka consumer threads with no
    // application code changes.
    // ────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("14: @Async read after parent write → PRIMARY (sticky is JVM-global)")
    void scenario14_asyncReadAfterParentWrite_underHybrid_routesToPrimary() throws Exception {
        svc.plainWriteOutsideTx(ALICE, "s14-" + UUID.randomUUID() + "@example.com");
        String result = svc.asyncReadAfterParentWrote(BOB_EMAIL).get(3, TimeUnit.SECONDS);
        assertThat(result).isEqualTo("PRIMARY");
    }

    // ────────────────────────────────────────────────────────────────────────
    // Scenario 15 — Kafka-style write (no tx, no HTTP) → PRIMARY
    // ────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("15: Kafka-style write (no tx) → PRIMARY")
    void scenario15_kafkaStyleWrite_routesToPrimary() {
        String newEmail = "s15-" + UUID.randomUUID() + "@example.com";
        assertThatCode(() -> svc.kafkaStyleWrite(ALICE, newEmail))
                .doesNotThrowAnyException();
        assertThat(countByEmail(primaryJdbc, newEmail)).isEqualTo(1L);
        assertThat(countByEmail(replicaJdbc, newEmail)).isEqualTo(0L);
    }

    // ────────────────────────────────────────────────────────────────────────
    // Scenario 16 — count() routes to PRIMARY
    //
    // Inherited JpaRepository methods (count, existsById, findAll, etc.) are
    // wrapped by Spring Data in a class-level @Transactional(readOnly=true).
    // ShardingSphere with transactionalReadQueryStrategy=PRIMARY pins reads
    // inside any open JDBC tx to write_ds. Same trade-off as scenario 5.
    //
    // Detection: insert a throwaway row directly on primary, count before
    // and after — if count routes to primary, it sees the row.
    // ────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("16: count() routes to PRIMARY (Spring Data wraps inherited methods in @Transactional(readOnly))")
    void scenario16_count_routesToPrimary() {
        long before = svc.countAll();
        UUID throwaway = UUID.randomUUID();
        primaryJdbc.update("INSERT INTO users (id, email, served_by) VALUES (?, ?, 'PRIMARY')",
                throwaway, "s16-" + throwaway);
        long after = svc.countAll();
        primaryJdbc.update("DELETE FROM users WHERE id = ?", throwaway);
        assertThat(after).isEqualTo(before + 1L);
    }

    // ────────────────────────────────────────────────────────────────────────
    // Scenario 17 — existsById() routes to PRIMARY
    // Same reason as scenario 16. Detection: insert a row only on primary,
    // exists must return true (replica would return false).
    // ────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("17: existsById() routes to PRIMARY (same Spring Data tx wrap)")
    void scenario17_existsById_routesToPrimary() {
        UUID primaryOnly = UUID.randomUUID();
        primaryJdbc.update("INSERT INTO users (id, email, served_by) VALUES (?, ?, 'PRIMARY')",
                primaryOnly, "s17-" + primaryOnly);
        try {
            assertThat(svc.exists(primaryOnly)).isTrue();
        } finally {
            primaryJdbc.update("DELETE FROM users WHERE id = ?", primaryOnly);
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // Scenario 18 — findAll(Pageable) routes to PRIMARY
    //
    // Same reason as 16/17. The page query emits TWO statements (a SELECT
    // and a count(*) for total pages); both are pinned to primary by the
    // wrapping read-only tx.
    //
    // Detection: marker column on the first returned row.
    // ────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("18: findAll(Pageable) routes to PRIMARY (page SELECT + count both pinned)")
    void scenario18_findAllPageable_routesToPrimary() {
        assertThat(svc.pageFirstRowServedBy()).isEqualTo("PRIMARY");
    }

    // ────────────────────────────────────────────────────────────────────────
    // Scenario 19 — saveAll(batch) outside tx → PRIMARY
    // Every row in the batch routes to primary, and the sticky-write recorder
    // refreshes lastWriteTime so a subsequent read also goes to primary.
    // ────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("19: saveAll(batch) outside tx → PRIMARY")
    void scenario19_saveAllBatch_outsideTx_routesToPrimary() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        User u1 = new User(); u1.setId(id1); u1.setEmail("s19-a-" + id1); u1.setServedBy("PRIMARY");
        User u2 = new User(); u2.setId(id2); u2.setEmail("s19-b-" + id2); u2.setServedBy("PRIMARY");
        assertThatCode(() -> svc.saveAllUsers(List.of(u1, u2))).doesNotThrowAnyException();
        assertThat(countById(primaryJdbc, id1)).isEqualTo(1L);
        assertThat(countById(primaryJdbc, id2)).isEqualTo(1L);
        assertThat(countById(replicaJdbc, id1)).isEqualTo(0L);
        // Sticky window refreshed → immediate read goes to primary.
        assertThat(svc.plainRead(ALICE_EMAIL)).isEqualTo("PRIMARY");
    }

    // ────────────────────────────────────────────────────────────────────────
    // Scenario 20 — deleteById() outside tx → PRIMARY
    // Recorder fires; subsequent read in sticky window goes to primary.
    // ────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("20: deleteById() outside tx → PRIMARY")
    void scenario20_deleteById_outsideTx_routesToPrimary() {
        // Insert a throwaway row on primary so deleteById has something to remove.
        UUID id = UUID.randomUUID();
        primaryJdbc.update("INSERT INTO users (id, email, served_by) VALUES (?, ?, 'PRIMARY')",
                id, "s20-" + id);
        assertThatCode(() -> svc.deleteOne(id)).doesNotThrowAnyException();
        assertThat(countById(primaryJdbc, id)).isEqualTo(0L);
        // Sticky window refreshed by the delete.
        assertThat(svc.plainRead(ALICE_EMAIL)).isEqualTo("PRIMARY");
    }

    // ────────────────────────────────────────────────────────────────────────
    // Scenario 21 — Rolled-back tx with write inside → next read → REPLICA
    //
    // Critical correctness test. The write happens, then a RuntimeException
    // rolls back the tx. afterCommit synchronization MUST NOT fire on
    // rollback, so StickyWriteContext.lastWriteTime is NOT refreshed. A read
    // immediately after the rolled-back tx goes to replica because no
    // sticky-write was recorded.
    //
    // A regression here would mean writes that didn't actually happen still
    // pin reads to primary — wasted capacity at best, masking of replication
    // delays at worst.
    // ────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("21: rolled-back tx with write inside → next read REPLICA (afterCommit skipped)")
    void scenario21_rolledBackTx_doesNotMarkSticky() {
        assertThatCode(() -> svc.writeThenRollback(ALICE))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("simulated rollback");
        // The UPDATE was rolled back; alice's email on primary is unchanged.
        assertThat(emailOnPrimary(ALICE)).isEqualTo(ALICE_EMAIL);
        // lastWriteTime was not refreshed → read goes to replica.
        assertThat(svc.plainRead(ALICE_EMAIL)).isEqualTo("REPLICA");
    }

    // ────────────────────────────────────────────────────────────────────────
    // Scenario 22 — @Transactional(REQUIRES_NEW) inner tx → PRIMARY
    //
    // Outer @Transactional + inner REQUIRES_NEW on a different bean opens a
    // fresh JDBC tx. ShardingSphere's transactionalReadQueryStrategy=PRIMARY
    // pins reads in any open JDBC tx — including this new inner one.
    // ────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("22: REQUIRES_NEW inner tx → PRIMARY (tx pinning applies to new tx)")
    void scenario22_requiresNewInnerTx_routesToPrimary() {
        assertThat(svc.outerThenRequiresNew(ALICE)).isEqualTo("PRIMARY");
    }

    // ────────────────────────────────────────────────────────────────────────
    // Scenario 23 — Hibernate dirty-check UPDATE → routes PRIMARY
    //
    // Two things to verify (and one known gap):
    //   - The dirty-check UPDATE itself routes to primary (ShardingSphere
    //     parses UPDATE → write_ds regardless of whether the caller used
    //     save() or just mutated the entity inside a tx).
    //   - The sticky-write recorder DOES NOT see this code path: its
    //     pointcut is save*/delete*/@Modifying, none of which fire here.
    //     So a subsequent read outside the tx goes to replica even though
    //     a write just happened. This is a known coverage gap; documented.
    // ────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("23: Hibernate dirty-check UPDATE → PRIMARY (sticky recorder doesn't see it — documented gap)")
    void scenario23_dirtyCheckUpdate_routesToPrimary_butStickyNotRefreshed() {
        assertThatCode(() -> svc.dirtyCheckUpdate(ALICE)).doesNotThrowAnyException();
        // UPDATE landed on primary (the email changed there, not on replica
        // which is read-only).
        assertThat(emailOnPrimary(ALICE)).startsWith("dirty-");
        // Known gap: recorder didn't fire because no save()/delete()/@Modifying.
        // A subsequent read goes to replica despite the just-committed write.
        assertThat(svc.plainRead(ALICE_EMAIL)).isEqualTo("REPLICA");
    }

    // ────────────────────────────────────────────────────────────────────────
    // Scenario 24 — Application code holds its own HintManager
    //
    // The bridge aspect tries getInstance() and catches IllegalStateException
    // (HintManager already open on this thread). It does not fight the caller,
    // simply proceeds — the caller's hint stays in force.
    // ────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("24: app code already holds HintManager → bridge respects it → PRIMARY")
    void scenario24_applicationHeldHint_bridgeDoesNotFight() {
        assertThat(svc.readWithApplicationHeldHint(ALICE_EMAIL)).isEqualTo("PRIMARY");
        // After the method returns, the application closed its HintManager
        // and force-master is not active. Next read flows to replica.
        assertThat(svc.plainRead(ALICE_EMAIL)).isEqualTo("REPLICA");
    }

    // ────────────────────────────────────────────────────────────────────────
    // Scenario 25 — @ForceMasterRead method throws → HintManager closed; no leak
    //
    // Critical robustness test. A repo call inside an @ForceMasterRead method
    // throws (orElseThrow on Optional.empty). The bridge's finally{} closes
    // HintManager; the outer ForceMasterReadAspect's finally{} clears the
    // ThreadLocal. Subsequent reads on the same pooled thread are not pinned.
    //
    // A regression would mean pooled threads bleed force-master state into
    // unrelated requests — every read on this thread until JVM shutdown
    // gets incorrectly pinned to primary.
    // ────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("25: @ForceMasterRead method throws → next read REPLICA (no thread state leak)")
    void scenario25_forceMasterThrows_doesNotLeakHint() {
        UUID nonExistent = UUID.randomUUID();
        assertThatCode(() -> svc.forceMasterButThrows(nonExistent))
                .isInstanceOf(RuntimeException.class);
        // Same thread, immediately after the throw: routing must be back to
        // default. No HintManager open, no ThreadLocal force-master flag.
        assertThat(svc.plainRead(ALICE_EMAIL)).isEqualTo("REPLICA");
    }

    // ────────────────────────────────────────────────────────────────────────
    // Scenario 26 — Sticky window refresh: second write extends the window
    //
    // Timeline:
    //   t=0     write 1   → lastWriteTime = 0
    //   t=3s    write 2   → lastWriteTime = 3s
    //   t=5.5s  read      → elapsed = 2.5s (from write 2) ≤ 5s → PRIMARY
    //   t=8.5s  read      → elapsed = 5.5s (from write 2) > 5s → REPLICA
    //
    // Verifies the refresh-on-newer-write semantics. Otherwise a long-running
    // session of writes would expire the window between writes even though
    // there's continuous write activity.
    // ────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("26: sticky window refresh — second write extends window")
    void scenario26_stickyWindowRefresh_secondWriteExtendsWindow() throws InterruptedException {
        // Write to alice (changes alice's email), then read bob (never mutated,
        // so findByEmail(BOB_EMAIL) still resolves on both DSes and the
        // returned served_by marker tells us which one served the SELECT).
        svc.plainWrite(ALICE);                             // t=0
        TimeUnit.MILLISECONDS.sleep(3000);
        svc.plainWrite(ALICE);                             // t=3s
        TimeUnit.MILLISECONDS.sleep(2500);                 // t=5.5s
        assertThat(svc.plainRead(BOB_EMAIL)).isEqualTo("PRIMARY");
        TimeUnit.MILLISECONDS.sleep(3000);                 // t=8.5s
        assertThat(svc.plainRead(BOB_EMAIL)).isEqualTo("REPLICA");
    }

    // ────────────────────────────────────────────────────────────────────────
    // Scenario 27 — Class-level @ForceMasterRead → PRIMARY
    //
    // ForceMasterReadAspect's pointcut is "@annotation OR @within" — the
    // @within clause matches annotations placed on the enclosing class. A
    // method on a class-annotated bean must still pin reads to primary.
    // ────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("27: class-level @ForceMasterRead → PRIMARY (@within pointcut)")
    void scenario27_classLevelForceMasterRead_routesToPrimary() {
        assertThat(classLevelForce.readByEmail(ALICE_EMAIL)).isEqualTo("PRIMARY");
    }

    // ────────────────────────────────────────────────────────────────────────
    // Scenario 28 — @StickyRead(0) falls back to the property default
    //
    // The annotation's value=0 (default) means "use the global window from
    // properties." Effective window resolves to stickyProps.windowMs (5000).
    // With a recent write, this should behave like the implicit sticky case.
    // ────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("28: @StickyRead with value=0 falls back to property default → PRIMARY")
    void scenario28_stickyReadZero_fallsBackToPropertyDefault() {
        svc.plainWriteOutsideTx(ALICE, "s28-prep-" + UUID.randomUUID() + "@example.com");
        assertThat(svc.stickyReadDefaultWindow(BOB_EMAIL)).isEqualTo("PRIMARY");
    }

    // ────────────────────────────────────────────────────────────────────────
    // Scenario 29 — Cross-thread sticky via raw Thread (not @Async)
    //
    // The async scenario (14) uses Spring's @Async executor; this one spawns
    // a raw new Thread() instead. Behavior should be identical because the
    // sticky propagation mechanism is JVM-global, not framework-specific.
    // Proves the design works for any thread, not just @Async / Kafka.
    // ────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("29: raw Thread read after parent write → PRIMARY (JVM-global sticky)")
    void scenario29_rawThreadReadAfterParentWrite_routesToPrimary() throws Exception {
        assertThat(svc.crossThreadReadAfterWrite(ALICE, BOB_EMAIL)).isEqualTo("PRIMARY");
    }

    // ────────────────────────────────────────────────────────────────────────
    // Scenario 30 — TransactionTemplate.execute() → reads inside → PRIMARY
    //
    // Some legacy code uses programmatic tx (TransactionTemplate) instead of
    // @Transactional. The library doesn't need to care — ShardingSphere's
    // transactionalReadQueryStrategy=PRIMARY applies to any open JDBC tx.
    // ────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("30: TransactionTemplate.execute() → reads inside → PRIMARY")
    void scenario30_transactionTemplateRead_routesToPrimary() {
        assertThat(svc.transactionTemplateRead(ALICE_EMAIL)).isEqualTo("PRIMARY");
    }

    // ────────────────────────────────────────────────────────────────────────
    // Scenario 31 — Hikari pool properties set via application.yml propagate
    // to the underlying HikariDataSource instances inside ShardingSphere.
    //
    // @DynamicPropertySource above sets:
    //   spring.datasource.write.hikari.maximum-pool-size: 7
    //   spring.datasource.read.hikari.maximum-pool-size:  11
    //
    // The library must plumb these through ShardingSphere's YAML so the
    // underlying pools honor them. Otherwise services migrating to this
    // library silently lose their Hikari pool tuning and fall back to
    // Hikari's defaults — a real regression for production-scale services.
    // ────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("31: spring.datasource.{write,read}.hikari.* properties reach the underlying pools")
    void scenario31_hikariProperties_propagateToUnderlyingPools() throws Exception {
        Map<String, HikariDataSource> pools = unwrapHikariPools(routingDataSource);
        HikariDataSource writePool = pools.get("write_ds");
        HikariDataSource readPool = pools.get("read_ds");
        assertThat(writePool.getMaximumPoolSize()).isEqualTo(7);
        assertThat(writePool.getMinimumIdle()).isEqualTo(2);
        assertThat(readPool.getMaximumPoolSize()).isEqualTo(11);
        assertThat(readPool.getMinimumIdle()).isEqualTo(3);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, HikariDataSource> unwrapHikariPools(javax.sql.DataSource ds) throws Exception {
        Field cmField = ds.getClass().getDeclaredField("contextManager");
        cmField.setAccessible(true);
        Object contextManager = cmField.get(ds);

        Field dbNameField = ds.getClass().getDeclaredField("databaseName");
        dbNameField.setAccessible(true);
        String databaseName = (String) dbNameField.get(ds);

        Map<String, ?> storageUnits = (Map<String, ?>) contextManager.getClass()
                .getMethod("getStorageUnits", String.class)
                .invoke(contextManager, databaseName);

        Map<String, HikariDataSource> result = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, ?> entry : storageUnits.entrySet()) {
            Object storageUnit = entry.getValue();
            javax.sql.DataSource wrapped = (javax.sql.DataSource) storageUnit.getClass()
                    .getMethod("getDataSource").invoke(storageUnit);
            // ShardingSphere wraps each pool in CatalogSwitchableDataSource;
            // peel the layer off via its public getDataSource().
            javax.sql.DataSource underlying = (javax.sql.DataSource) wrapped.getClass()
                    .getMethod("getDataSource").invoke(wrapped);
            result.put(entry.getKey(), (HikariDataSource) underlying);
        }
        return result;
    }

    private String emailOnPrimary(UUID id) {
        return primaryJdbc.queryForObject("SELECT email FROM users WHERE id = ?", String.class, id);
    }

    private static Long countByEmail(JdbcTemplate jt, String email) {
        return jt.queryForObject("SELECT count(*) FROM users WHERE email = ?", Long.class, email);
    }

    private static Long countById(JdbcTemplate jt, UUID id) {
        return jt.queryForObject("SELECT count(*) FROM users WHERE id = ?", Long.class, id);
    }
}
