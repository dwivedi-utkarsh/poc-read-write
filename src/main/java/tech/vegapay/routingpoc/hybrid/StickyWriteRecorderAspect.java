package tech.vegapay.routingpoc.hybrid;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tech.vegapay.readwriteseperationlibrary.context.stickyReadWriteContext.StickyWriteContext;

/**
 * Updates {@link StickyWriteContext#markWrite()} after every Spring Data
 * repository write so the sticky window has a meaningful lastWriteTime to
 * compare against.
 *
 * In the in-house library this responsibility lives inside
 * RoutingDataSource.registerWriteSynchronizationIfNeeded() — it registers an
 * afterCommit hook on every write tx. Under the hybrid wiring
 * RoutingDataSource is not present, so we replicate that mechanic here.
 *
 * Why an aspect and not Hibernate event listeners: Hibernate's
 * PostInsert/Update/Delete listeners do not fire for bulk @Modifying JPQL
 * updates or native INSERT/UPDATE statements. The audited consumer services
 * use both shapes heavily, so listener-only coverage would miss scenario 3
 * and scenario 11 silently. An aspect on Spring Data write methods catches
 * every code path that goes through a repository.
 *
 * Pointcut targets save... and delete... on CrudRepository and any method
 * annotated with @Modifying. Inside a Spring tx, registers an afterCommit
 * hook so the timestamp is set only on success; outside a tx (autocommit),
 * marks immediately — the JDBC statement has already committed at that point.
 *
 * Not a @Component; instantiated via HybridTestConfig.
 */
@Aspect
@Slf4j
public class StickyWriteRecorderAspect {

    @AfterReturning(
            "execution(* org.springframework.data.repository.CrudRepository+.save*(..)) " +
                    "|| execution(* org.springframework.data.repository.CrudRepository+.delete*(..)) " +
                    "|| @annotation(org.springframework.data.jpa.repository.Modifying)"
    )
    public void recordWrite(JoinPoint jp) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    StickyWriteContext.markWrite();
                    if (log.isDebugEnabled()) {
                        log.debug("Sticky write recorded (afterCommit) for {}",
                                jp.getSignature().toShortString());
                    }
                }
            });
        } else {
            // No active Spring tx → the underlying statement has already
            // committed via JDBC autocommit by the time we get here. Safe to
            // mark immediately.
            StickyWriteContext.markWrite();
            if (log.isDebugEnabled()) {
                log.debug("Sticky write recorded (autocommit) for {}",
                        jp.getSignature().toShortString());
            }
        }
    }
}
