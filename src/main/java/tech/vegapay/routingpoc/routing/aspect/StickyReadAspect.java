package tech.vegapay.routingpoc.routing.aspect;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import tech.vegapay.routingpoc.routing.annotation.StickyRead;
import tech.vegapay.routingpoc.routing.context.RoutingContext;

/**
 * Around-advice (not Before) so the override is cleared on exit. The in-house
 * library used @Before, which left the override leaking until the next call;
 * a quiet correctness fix while we're rewriting.
 */
@Aspect
public class StickyReadAspect {

    @Around("@annotation(stickyRead)")
    public Object enableSticky(ProceedingJoinPoint pjp, StickyRead stickyRead) throws Throwable {
        long previous = RoutingContext.getStickyReadOverrideMs();
        try {
            RoutingContext.enableStickyReadOverride(stickyRead.value());
            return pjp.proceed();
        } finally {
            if (previous > 0) {
                RoutingContext.enableStickyReadOverride(previous);
            } else {
                RoutingContext.clearStickyReadOverride();
            }
        }
    }
}
