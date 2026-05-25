package tech.vegapay.routingpoc.hybrid;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tech.vegapay.readwriteseperationlibrary.annotation.ForceMasterRead;
import tech.vegapay.readwriteseperationlibrary.annotation.StickyRead;
import tech.vegapay.routingpoc.User;
import tech.vegapay.routingpoc.UserRepo;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Service surface exercised by the 30-scenario integration matrix. Routing
 * intent is expressed using the library's annotations ({@link ForceMasterRead},
 * {@link StickyRead}); the HintManagerBridgeAspect inside the library
 * translates that intent into ShardingSphere HintManager calls.
 */
@Service
@RequiredArgsConstructor
public class HybridUserService {

    private final UserRepo repo;
    private final NestedTxService nestedTxService;
    private final PlatformTransactionManager transactionManager;

    // ── Scenarios 1-15: existing surface ────────────────────────────────────

    @Transactional(readOnly = true)
    public String readInReadOnlyTx(String email) {
        return repo.findByEmail(email).getServedBy();
    }

    @Transactional
    public String readThenWriteInTx(UUID id) {
        String before = repo.findById(id).orElseThrow(IllegalStateException::new).getServedBy();
        repo.updateEmail(id, "tx-" + UUID.randomUUID() + "@example.com");
        return before;
    }

    @Transactional
    public String lockAndUpdate(UUID id) {
        String before = repo.findByIdForUpdate(id).getServedBy();
        repo.updateEmail(id, "lock-" + UUID.randomUUID() + "@example.com");
        return before;
    }

    public String writeThenReadOutsideTx(UUID id, String email) {
        repo.updateEmail(id, "sticky-" + UUID.randomUUID() + "@example.com");
        return repo.findByEmail(email).getServedBy();
    }

    @ForceMasterRead
    public String forceMasterRead(String email) {
        return repo.findByEmail(email).getServedBy();
    }

    @StickyRead(5000)
    public String stickyReadWidened(String email) {
        return repo.findByEmail(email).getServedBy();
    }

    public String plainRead(String email) {
        return repo.findByEmail(email).getServedBy();
    }

    public String nativeRead(String email) {
        return repo.findByEmailNative(email).getServedBy();
    }

    public String jpqlRead(String email) {
        return repo.findByEmailJpql(email).getServedBy();
    }

    public int plainWriteOutsideTx(UUID id, String email) {
        return repo.updateEmail(id, email);
    }

    public int nativeInsertOutsideTx(UUID id, String email) {
        return repo.insertNative(id, email);
    }

    @Async
    public CompletableFuture<String> asyncReadAfterParentWrote(String email) {
        return CompletableFuture.completedFuture(repo.findByEmail(email).getServedBy());
    }

    public void kafkaStyleWrite(UUID id, String email) {
        repo.updateEmail(id, email);
    }

    // ── Scenarios 16-30: new surface ────────────────────────────────────────

    /** 16: plain count(). */
    public long countAll() {
        return repo.count();
    }

    /** 17: existsById(). */
    public boolean exists(UUID id) {
        return repo.existsById(id);
    }

    /** 18: findAll with Pageable — emits a SELECT and a count(*) query. */
    public String pageFirstRowServedBy() {
        Page<User> page = repo.findAll(PageRequest.of(0, 1));
        return page.getContent().isEmpty() ? null : page.getContent().get(0).getServedBy();
    }

    /** 19: saveAll batch write. */
    public List<User> saveAllUsers(List<User> users) {
        return repo.saveAll(users);
    }

    /** 20: deleteById. */
    public void deleteOne(UUID id) {
        repo.deleteById(id);
    }

    /**
     * 21: write inside @Transactional then throw — afterCommit must NOT fire,
     * so sticky-window's lastWriteTime must NOT be refreshed.
     */
    @Transactional
    public void writeThenRollback(UUID id) {
        repo.updateEmail(id, "rollback-" + UUID.randomUUID() + "@example.com");
        throw new RuntimeException("simulated rollback");
    }

    /** 22: outer tx then REQUIRES_NEW inner tx. The inner is a fresh JDBC tx. */
    @Transactional
    public String outerThenRequiresNew(UUID id) {
        return nestedTxService.requiresNewRead(id);
    }

    /**
     * 23: Hibernate dirty-check — fetch entity, mutate field, no save() call.
     * Hibernate emits UPDATE on flush/commit. The UPDATE routes to primary,
     * but the sticky-write recorder does NOT see this code path (the
     * recorder pointcut matches save / delete / Modifying repository
     * methods, none of which fire here), so lastWriteTime is not refreshed.
     */
    @Transactional
    public void dirtyCheckUpdate(UUID id) {
        User u = repo.findById(id).orElseThrow(IllegalStateException::new);
        u.setEmail("dirty-" + UUID.randomUUID() + "@example.com");
        // No save() — Hibernate flushes UPDATE on commit via dirty checking.
    }

    /**
     * 24: application code opens its own HintManager. The bridge aspect must
     * detect this and not fight it. Imports of ShardingSphere classes here
     * are intentional — this simulates a consumer-service call site that
     * needs the fine-grained control.
     */
    public String readWithApplicationHeldHint(String email) {
        org.apache.shardingsphere.infra.hint.HintManager hint =
                org.apache.shardingsphere.infra.hint.HintManager.getInstance();
        try {
            hint.setWriteRouteOnly();
            return repo.findByEmail(email).getServedBy();
        } finally {
            hint.close();
        }
    }

    /**
     * 25: @ForceMasterRead method that throws partway. The bridge aspect's
     * finally{} must close the HintManager so subsequent unrelated reads on
     * the same pooled thread are NOT incorrectly pinned to primary.
     */
    @ForceMasterRead
    public String forceMasterButThrows(UUID nonExistentId) {
        return repo.findById(nonExistentId)
                .orElseThrow(() -> new RuntimeException("simulated failure"))
                .getServedBy();
    }

    /** 26: helper for the sticky-window-refresh test — just a write. */
    public void plainWrite(UUID id) {
        repo.updateEmail(id, "refresh-" + UUID.randomUUID() + "@example.com");
    }

    /**
     * 28: @StickyRead with value=0 — should fall back to the property default
     * (5000 ms), so behaves like the global window when a recent write exists.
     */
    @StickyRead
    public String stickyReadDefaultWindow(String email) {
        return repo.findByEmail(email).getServedBy();
    }

    /**
     * 29: parent thread writes, then spawns a raw Thread (not @Async). The
     * child thread enters the bridge aspect with no ThreadLocal context but
     * sees the JVM-global lastWriteTime set by the parent.
     */
    public String crossThreadReadAfterWrite(UUID writeId, String readEmail) throws InterruptedException {
        repo.updateEmail(writeId, "xthread-" + UUID.randomUUID() + "@example.com");
        AtomicReference<String> result = new AtomicReference<>();
        Thread t = new Thread(() -> result.set(repo.findByEmail(readEmail).getServedBy()));
        t.start();
        t.join(3000);
        return result.get();
    }

    /** 30: programmatic transaction via TransactionTemplate.execute. */
    public String transactionTemplateRead(String email) {
        TransactionTemplate tt = new TransactionTemplate(transactionManager);
        return tt.execute(status -> repo.findByEmail(email).getServedBy());
    }
}
