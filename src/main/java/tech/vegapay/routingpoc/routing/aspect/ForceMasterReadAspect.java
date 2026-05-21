package tech.vegapay.routingpoc.routing.aspect;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import tech.vegapay.routingpoc.routing.context.RoutingContext;

@Aspect
public class ForceMasterReadAspect {

    @Around(
            "@annotation(tech.vegapay.routingpoc.routing.annotation.ForceMasterRead) || " +
            "@within(tech.vegapay.routingpoc.routing.annotation.ForceMasterRead)"
    )
    public Object forceMaster(ProceedingJoinPoint pjp) throws Throwable {
        try {
            RoutingContext.setForceMaster(true);
            return pjp.proceed();
        } finally {
            RoutingContext.clearForceMaster();
        }
    }
}
