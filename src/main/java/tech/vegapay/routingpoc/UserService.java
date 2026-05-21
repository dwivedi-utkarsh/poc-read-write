package tech.vegapay.routingpoc;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.vegapay.routingpoc.routing.annotation.ForceMasterRead;
import tech.vegapay.routingpoc.routing.annotation.StickyRead;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Service surface exercised by the routing test. Each method maps to one
 * scenario in the matrix. Routing intent is expressed using the local
 * annotations ({@link ForceMasterRead}, {@link StickyRead}); the
 * HintManagerBridgeAspect translates that intent into ShardingSphere
 * HintManager calls.
 */
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepo repo;

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

    /**
     * No Spring tx. The bridge aspect should observe RoutingContext's
     * recently-updated lastWriteTime (set by StickyWriteRecorderAspect after
     * the UPDATE returned) and pin the subsequent SELECT to primary.
     */
    public String writeThenReadOutsideTx(UUID id, String email) {
        repo.updateEmail(id, "sticky-" + UUID.randomUUID() + "@example.com");
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
