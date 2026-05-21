package tech.vegapay.routingpoc.routing;

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
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tech.vegapay.routingpoc.RoutingPocApplication;
import tech.vegapay.routingpoc.User;
import tech.vegapay.routingpoc.UserRepo;
import tech.vegapay.routingpoc.UserService;
import tech.vegapay.routingpoc.routing.context.RoutingContext;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * ShardingSphere-only run of the 15-scenario routing matrix. No external
 * library dependency: annotations, sticky-window context, and aspects all
 * live locally under tech.vegapay.routingpoc.routing. ShardingSphere makes
 * the routing decisions; the local aspects translate annotation intent and
 * sticky-window state into HintManager hints.
 *
 * Scenario 5 (@Transactional(readOnly=true)) routes to PRIMARY — that is a
 * transactionalReadQueryStrategy=PRIMARY trade-off, not something the aspect
 * layer can fix.
 */
@SpringBootTest(classes = {
        RoutingPocApplication.class,
        RoutingIntegrationTest.ContainersConfig.class
})
@Import(RoutingTestConfig.class)
@ActiveProfiles("test")
@Testcontainers
class RoutingIntegrationTest {

    private static final UUID ALICE = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String ALICE_EMAIL = "alice@example.com";

    // Bob is never mutated by any scenario, so reads against bob's email
    // succeed regardless of which DB serves them — the marker column tells us
    // which one did. Tests that combine a write (to alice) with a read (for
    // routing-detection purposes) use bob as the read target.
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
    UserService svc;

    @Autowired
    @Qualifier("primaryJdbc")
    JdbcTemplate primaryJdbc;

    @Autowired
    @Qualifier("replicaJdbc")
    JdbcTemplate replicaJdbc;

    @BeforeEach
    void resetAliceAndRoutingState() {
        primaryJdbc.update("UPDATE users SET email = ? WHERE id = ?", ALICE_EMAIL, ALICE);
        RoutingContext.resetAll();
    }

    @AfterEach
    void clearStateBetweenTests() {
        RoutingContext.resetAll();
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
    // Scenario 5 — @Transactional(readOnly = true) → PRIMARY (trade-off).
    //
    // ShardingSphere pins every read inside a JDBC tx to the write DS when
    // transactionalReadQueryStrategy=PRIMARY, and there is no HintManager
    // call that means "force read DS for this query" to counteract it. The
    // trade-off is intentional: we want write-tx pinning (scenarios 6 and 7).
    //
    // If this scenario needs to flip:
    //   - Change ShardingSphere to transactionalReadQueryStrategy=DYNAMIC
    //     (and accept the consequences on scenarios 6/7), OR
    //   - Drop @Transactional(readOnly=true) at the call site and let the
    //     SELECT execute outside any tx (becomes the scenario 1 path).
    // ────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("5: @Transactional(readOnly=true) SELECT → PRIMARY (transactional-pinning trade-off)")
    void scenario05_readOnlyTx_routesToPrimary() {
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
    // Scenario 8 — read within sticky window after a committed write outside tx.
    //
    // StickyWriteRecorderAspect marks the timestamp on UPDATE return,
    // HintManagerBridgeAspect sees the recent write and pins the subsequent
    // SELECT to write_ds. This is the headline read-your-own-write fix.
    // ────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("8: read after write within sticky window → PRIMARY")
    void scenario08_readAfterWriteOutsideTx_routesToPrimary() {
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
    // ────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("12: @ForceMasterRead annotation → PRIMARY")
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
    // RoutingContext.lastWriteTime is a static volatile long — JVM-wide, not
    // ThreadLocal. The async thread enters HintManagerBridgeAspect and sees
    // the same lastWriteTime the parent thread set; sticky pins the SELECT
    // to primary. Read-your-own-write transparently extends to @Async /
    // Kafka consumer threads with no application code changes.
    // ────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("14: @Async read after parent write → PRIMARY (sticky is JVM-global)")
    void scenario14_asyncReadAfterParentWrite_routesToPrimary() throws Exception {
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

    private static Long countByEmail(JdbcTemplate jt, String email) {
        return jt.queryForObject("SELECT count(*) FROM users WHERE email = ?", Long.class, email);
    }

    private static Long countById(JdbcTemplate jt, UUID id) {
        return jt.queryForObject("SELECT count(*) FROM users WHERE id = ?", Long.class, id);
    }
}
