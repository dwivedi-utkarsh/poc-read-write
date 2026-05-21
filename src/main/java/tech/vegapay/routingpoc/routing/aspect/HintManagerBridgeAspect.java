package tech.vegapay.routingpoc.routing.aspect;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.shardingsphere.infra.hint.HintManager;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.annotation.Order;
import tech.vegapay.routingpoc.routing.config.StickyWriteProperties;
import tech.vegapay.routingpoc.routing.context.RoutingContext;

/**
 * At every Spring Data repository method entry: peek at RoutingContext. If any
 * signal says "pin to primary," open a {@link HintManager} for the duration of
 * the call and flip it to write-route-only. ShardingSphere's read/write
 * splitting rule consults the hint and routes the SELECT to write_ds.
 *
 * Three pin signals, in order:
 *   1. @ForceMasterRead → RoutingContext.isForceMaster()
 *   2. @StickyRead-widened window → recent write inside per-thread override
 *   3. Default sticky-window from properties → recent write inside global window
 */
@Aspect
@Order(Integer.MAX_VALUE)
@RequiredArgsConstructor
@Slf4j
public class HintManagerBridgeAspect {

    private final StickyWriteProperties stickyProps;

    @Around("execution(* org.springframework.data.repository.Repository+.*(..))")
    public Object bridge(ProceedingJoinPoint pjp) throws Throwable {
        if (!shouldPinToPrimary()) {
            return pjp.proceed();
        }

        HintManager hint;
        try {
            hint = HintManager.getInstance();
        } catch (IllegalStateException alreadyHeld) {
            // Caller higher in the stack already owns the HintManager for this
            // thread — trust their hint, don't wrestle.
            return pjp.proceed();
        }

        try {
            hint.setWriteRouteOnly();
            if (log.isDebugEnabled()) {
                log.debug("Bridge → pinning {} to write_ds", pjp.getSignature().toShortString());
            }
            return pjp.proceed();
        } finally {
            hint.close();
        }
    }

    private boolean shouldPinToPrimary() {
        if (RoutingContext.isForceMaster()) {
            return true;
        }
        long window = effectiveWindow();
        if (window <= 0) {
            return false;
        }
        long lastWrite = RoutingContext.getLastWriteTime();
        if (lastWrite <= 0) {
            return false;
        }
        return (System.currentTimeMillis() - lastWrite) <= window;
    }

    private long effectiveWindow() {
        long override = RoutingContext.getStickyReadOverrideMs();
        if (override > 0) {
            return override;
        }
        if (stickyProps != null && stickyProps.isEnabled()) {
            return stickyProps.getWindowMs();
        }
        return 0;
    }
}
