package tech.vegapay.routingpoc.hybrid;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.vegapay.readwriteseperationlibrary.annotation.ForceMasterRead;
import tech.vegapay.readwriteseperationlibrary.annotation.StickyRead;
import tech.vegapay.routingpoc.UserRepo;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * The service surface exercised by the hybrid test. Each method maps to one
 * scenario in the matrix. Routing intent is expressed using the in-house
 * library's annotations ({@link ForceMasterRead}, {@link StickyRead}); the
 * HintManagerBridgeAspect translates that intent into ShardingSphere
 * HintManager calls.
 *
 * Lives in main (not test) because this is the pattern a consumer service
 * would ship in production — call sites stay annotation-driven, infra
 * picks the engine.
 */
@Service
@RequiredArgsConstructor
public class HybridUserService {

    private final UserRepo repo;

    @Transactional(readOnly = true)
    public String readInReadOnlyTx(String email) {
        return repo.findByEmail(email).getServedBy();
    }

    @Transactional
    public String readThenWriteInTx(UUID id) {
        String before = repo.findById(id).orElseThrow(IllegalStateException::new).getServedBy();
        repo.updateEmail(id, "hybrid-tx-" + UUID.randomUUID() + "@example.com");
        return before;
    }

    @Transactional
    public String lockAndUpdate(UUID id) {
        String before = repo.findByIdForUpdate(id).getServedBy();
        repo.updateEmail(id, "hybrid-lock-" + UUID.randomUUID() + "@example.com");
        return before;
    }

    /**
     * No Spring tx. The bridge aspect should observe StickyWriteContext's
     * recently-updated lastWriteTime (set by StickyWriteRecorderAspect after
     * the UPDATE returned) and pin the subsequent SELECT to primary.
     */
    public String writeThenReadOutsideTx(UUID id, String email) {
        repo.updateEmail(id, "hybrid-sticky-" + UUID.randomUUID() + "@example.com");
        return repo.findByEmail(email).getServedBy();
    }

    @ForceMasterRead
    public String forceMasterRead(String email) {
        return repo.findByEmail(email).getServedBy();
    }

    /**
     * StickyRead widens the sticky window for this thread to the given ms. The
     * caller is expected to have made a recent write whose timestamp this
     * widened window now covers.
     */
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
}
