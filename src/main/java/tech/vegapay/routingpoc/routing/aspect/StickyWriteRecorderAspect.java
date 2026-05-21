package tech.vegapay.routingpoc.routing.aspect;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tech.vegapay.routingpoc.routing.context.RoutingContext;

/**
 * Stamps {@link RoutingContext#markWrite()} after every Spring Data repository
 * write so the sticky window has a meaningful lastWriteTime to compare against.
 *
 * Why an aspect and not Hibernate event listeners: Hibernate's
 * PostInsert/Update/Delete listeners do not fire for bulk @Modifying JPQL
 * updates or native INSERT/UPDATE statements. An aspect on Spring Data write
 * methods catches every code path that goes through a repository.
 *
 * Inside a Spring tx → register an afterCommit hook so the timestamp is set
 * only on success. Outside a tx (autocommit) → mark immediately; the JDBC
 * statement has already committed by the time we get here.
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
                    RoutingContext.markWrite();
                    if (log.isDebugEnabled()) {
                        log.debug("Sticky write recorded (afterCommit) for {}",
                                jp.getSignature().toShortString());
                    }
                }
            });
        } else {
            RoutingContext.markWrite();
            if (log.isDebugEnabled()) {
                log.debug("Sticky write recorded (autocommit) for {}",
                        jp.getSignature().toShortString());
            }
        }
    }
}
