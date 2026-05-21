package tech.vegapay.routingpoc.routing.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Widens the sticky-read window for the current thread for the duration of the
 * annotated method. value() is the override window in milliseconds.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface StickyRead {
    long value() default 0;
}
