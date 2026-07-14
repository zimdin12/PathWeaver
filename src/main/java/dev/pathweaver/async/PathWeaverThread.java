package dev.pathweaver.async;

/**
 * Marks whether the current thread is a PathWeaver worker running an A* search off the main thread.
 *
 * <p>This is the hook the pathfinding mixins consult to isolate off-thread searches from live shared
 * state: when {@link #isWorker()} is true, {@code PathfindingContextMixin} hands the search a fresh,
 * thread-confined {@code PathTypeCache} instead of the {@code ServerLevel}'s shared one, and the
 * evaluator mixins skip the live-mob {@code onPathfindingStart/Done} callbacks. On the main thread
 * {@link #isWorker()} is false, so these particular redirects call vanilla's original targets.</p>
 *
 * <p>The flag is set/cleared around every search in {@link PathWorkerPool}, so ALL worker execution is
 * covered regardless of which Callable was submitted.</p>
 */
public final class PathWeaverThread {
    // Not an inheritable ThreadLocal: worker threads are the only ones that should ever see true, and
    // they never spawn children that do pathfinding. Default false everywhere (incl. the main thread).
    private static final ThreadLocal<Boolean> WORKER = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private PathWeaverThread() {}

    /** True only while a PathWeaver worker is executing a search Callable. */
    public static boolean isWorker() {
        return WORKER.get();
    }

    /** Set by {@link PathWorkerPool} at the very start of a worker search. */
    public static void enterWorker() {
        WORKER.set(Boolean.TRUE);
    }

    /** Cleared by {@link PathWorkerPool} in a finally after the search, so pooled threads reset cleanly. */
    public static void exitWorker() {
        WORKER.set(Boolean.FALSE);
    }
}
