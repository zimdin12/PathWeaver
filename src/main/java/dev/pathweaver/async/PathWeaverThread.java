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

    /**
     * A randomness source confined to one worker thread.
     *
     * <p>{@code FlyNodeEvaluator} picks its start node from a randomly chosen candidate position and
     * draws that choice from {@code Mob.getRandom()}, which is shared live entity state. Reading it
     * from a worker is a data race, and it is the only reason flying mobs pathed synchronously.
     *
     * <p>The search does not need the mob's randomness, only <em>some</em> randomness: vanilla's
     * contract is that the start candidate is arbitrary, so any draw satisfies it. A thread-confined
     * source removes the race without changing what the search is allowed to return. The mob's own
     * RNG sequence is not advanced during an async search, which does diverge from vanilla's
     * sequence — Minecraft offers no reproducibility guarantee there, and trading an unobservable
     * sequence difference for a real race is the right way round.
     */
    private static final ThreadLocal<net.minecraft.util.RandomSource> WORKER_RANDOM =
        ThreadLocal.withInitial(net.minecraft.util.RandomSource::create);

    /**
     * Set on the MAIN thread while it runs an off-thread search's prologue.
     *
     * <p>{@code NodeEvaluator.prepare} constructs the search's {@code PathfindingContext}, and that
     * constructor grabs the level's shared {@code PathTypeCache}. Isolating it keyed on
     * {@link #isWorker()} alone, which was correct while {@code prepare} ran on the worker — and
     * silently wrong the moment 0.4.0 moved it to the main thread, because the context was then
     * built around the shared cache and handed to a worker that writes through it.
     *
     * <p>What decides isolation is which thread will <em>use</em> the context, not which thread
     * happens to build it.
     */
    private static final ThreadLocal<Boolean> PREPARING_FOR_WORKER =
        ThreadLocal.withInitial(() -> Boolean.FALSE);

    private PathWeaverThread() {}

    /** True only while a PathWeaver worker is executing a search Callable. */
    public static boolean isWorker() {
        return WORKER.get();
    }

    /**
     * True when the search this code is serving will run off the main thread — either because a
     * worker is running it now, or because the main thread is preparing it for one.
     *
     * <p>This is the condition every shared-state isolation decision must use.
     */
    public static boolean searchRunsOffThread() {
        return WORKER.get() || PREPARING_FOR_WORKER.get();
    }

    /** Scope the prologue the main thread runs on a worker's behalf. Always paired in a finally. */
    public static void enterAsyncPrologue() {
        PREPARING_FOR_WORKER.set(Boolean.TRUE);
    }

    public static void exitAsyncPrologue() {
        PREPARING_FOR_WORKER.set(Boolean.FALSE);
    }

    /** The calling worker's own randomness. Never call from the main thread; use the mob's own. */
    public static net.minecraft.util.RandomSource workerRandom() {
        return WORKER_RANDOM.get();
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
