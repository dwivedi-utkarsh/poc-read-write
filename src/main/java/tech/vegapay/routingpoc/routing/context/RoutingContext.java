package tech.vegapay.routingpoc.routing.context;

/**
 * Thread-local + JVM-global state that drives the routing decision. Collapses
 * what the in-house library spread across three classes
 * (RoutingContextHolder, StickyReadContext, StickyWriteContext) into one,
 * because all three are consumed at the same call site (the bridge aspect).
 *
 * Three independent signals:
 *   - forceMaster (ThreadLocal): set by ForceMasterReadAspect.
 *   - stickyReadOverrideMs (ThreadLocal): set by StickyReadAspect; widens
 *     the sticky window for this thread only.
 *   - lastWriteTime (static volatile): refreshed by StickyWriteRecorderAspect
 *     after every repository write. Deliberately JVM-global, not ThreadLocal,
 *     so read-your-own-write transparently extends to @Async threads.
 */
public final class RoutingContext {

    private static final ThreadLocal<Boolean> FORCE_MASTER =
            ThreadLocal.withInitial(() -> Boolean.FALSE);

    private static final ThreadLocal<Long> STICKY_READ_OVERRIDE_MS = new ThreadLocal<>();

    private static volatile long lastWriteTime = 0L;

    private RoutingContext() {
    }

    public static void setForceMaster(boolean force) {
        FORCE_MASTER.set(force);
    }

    public static boolean isForceMaster() {
        return FORCE_MASTER.get();
    }

    public static void clearForceMaster() {
        FORCE_MASTER.remove();
    }

    public static void enableStickyReadOverride(long windowMs) {
        STICKY_READ_OVERRIDE_MS.set(windowMs);
    }

    public static long getStickyReadOverrideMs() {
        Long v = STICKY_READ_OVERRIDE_MS.get();
        return v == null ? 0L : v;
    }

    public static void clearStickyReadOverride() {
        STICKY_READ_OVERRIDE_MS.remove();
    }

    public static void markWrite() {
        lastWriteTime = System.currentTimeMillis();
    }

    public static long getLastWriteTime() {
        return lastWriteTime;
    }

    /** Test-only: reset everything to a clean state between scenarios. */
    public static void resetAll() {
        clearForceMaster();
        clearStickyReadOverride();
        lastWriteTime = 0L;
    }
}
