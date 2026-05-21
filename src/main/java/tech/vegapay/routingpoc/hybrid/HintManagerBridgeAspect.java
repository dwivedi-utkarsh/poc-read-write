package tech.vegapay.routingpoc.hybrid;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.shardingsphere.infra.hint.HintManager;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.annotation.Order;
import tech.vegapay.readwriteseperationlibrary.config.properties.StickyWriteProperties;
import tech.vegapay.readwriteseperationlibrary.context.RoutingContextHolder;
import tech.vegapay.readwriteseperationlibrary.context.stickyReadWriteContext.StickyReadContext;
import tech.vegapay.readwriteseperationlibrary.context.stickyReadWriteContext.StickyWriteContext;

/**
 * Bridge between the in-house read-write-seperation-library and ShardingSphere.
 *
 * The library still drives intent — its ForceMasterReadAspect sets
 * {@link RoutingContextHolder#setForceMaster(boolean)} when a method is
 * annotated with @ForceMasterRead; its StickyReadAspect populates
 * {@link StickyReadContext} from @StickyRead; {@link StickyWriteContext} holds
 * the JVM-global lastWriteTime maintained by {@link StickyWriteRecorderAspect}.
 *
 * In the library's original wiring those ThreadLocals were consumed by
 * RoutingDataSource.determineCurrentLookupKey(). Under the hybrid wiring
 * RoutingDataSource is not in the bean graph; ShardingSphere is. This aspect
 * fills the gap: at every Spring Data repository method entry it reads the
 * library's ThreadLocals and, when any signal says "pin to primary", opens a
 * {@link HintManager} for the duration of the call and flips it to
 * write-route-only. ShardingSphere's read/write splitting rule consults the
 * hint and routes the SELECT to write_ds even though it would otherwise have
 * gone through the load balancer to read_ds.
 *
 * Not a @Component — wired explicitly via HybridTestConfig so the Approach D
 * baseline test (which doesn't import the library aspects or
 * StickyWriteProperties) is not affected by this advice running.
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
            // A caller higher in the stack already owns the HintManager for
            // this thread (e.g. the application explicitly opened one). Don't
            // wrestle with them — trust the existing hint.
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
        if (RoutingContextHolder.isForceMaster()) {
            return true;
        }
        return isWithinStickyWindow();
    }

    private boolean isWithinStickyWindow() {
        long window = effectiveWindow();
        if (window <= 0) {
            return false;
        }
        long lastWrite = StickyWriteContext.getLastWriteTime();
        if (lastWrite <= 0) {
            return false;
        }
        return (System.currentTimeMillis() - lastWrite) <= window;
    }

    private long effectiveWindow() {
        long override = StickyReadContext.getOverrideWindowMs();
        if (override > 0) {
            return override;
        }
        if (stickyProps != null && stickyProps.isEnabled()) {
            return stickyProps.getWindowMs();
        }
        return 0;
    }
}
