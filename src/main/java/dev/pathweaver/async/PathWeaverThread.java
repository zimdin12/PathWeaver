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

    /**
     * The mob's step height, resolved on the main thread at dispatch and read by the worker instead
     * of calling {@code Mob.maxUpStep()}.
     *
     * <p>{@code maxUpStep()} looks like a read and is not. It resolves to
     * {@code AttributeInstance.getValue()}, which on 26.1.2 is a read-modify-write:
     *
     * <pre>{@code if (this.dirty) { this.cachedValue = calculateValue(); this.dirty = false; }}</pre>
     *
     * <p>Both fields are plain and non-volatile, and {@code calculateValue()} walks the modifier
     * collections while the main thread may be adding or removing modifiers. {@code WalkNodeEvaluator}
     * calls it from {@code getNeighbors} and {@code getMobJumpHeight} — inside the A* loop, hundreds
     * of times per search. So this is a live data race on shared entity state, in the middle of the
     * one region the design claims contains only reads.
     *
     * <p>A dispatch-time pre-resolve was tried first and is not sufficient: it clears {@code dirty}
     * at that instant, but anything that touches the attribute afterwards — equipment, a potion
     * effect, a mod — sets it again while the search is still in flight. The failure is worse than a
     * thrown exception: the worker can publish {@code dirty = false} without {@code cachedValue}
     * being visible to the main thread, leaving the mob's step height permanently wrong for the rest
     * of the session with nothing in the log.
     *
     * <p>Same shape as {@link #WORKER_RANDOM}, and for the same reason: the search does not need the
     * live value, it needs <em>a</em> value fixed for the duration. Vanilla resolves it once per
     * search anyway, microseconds after the prologue, so a value captured at dispatch is what a
     * synchronous search would have observed.
     */
    private static final ThreadLocal<Float> WORKER_STEP_HEIGHT = new ThreadLocal<>();

    /**
     * The mob's max fall distance, resolved on the main thread at dispatch.
     *
     * <p>The same hazard as {@link #WORKER_STEP_HEIGHT} reached by a different route, and it was
     * missed when that one was fixed. {@code WalkNodeEvaluator.tryFindFirstGroundNodeBelow} — reached
     * from {@code getNeighbors} via {@code findAcceptedNode}, so inside the A* loop — calls
     * {@code Mob.getMaxFallDistance()}, which for a mob with a target reads {@code getMaxHealth()}
     * and therefore {@code AttributeInstance.getValue()}: the same
     * {@code if (dirty) { cachedValue = calculateValue(); dirty = false; }} over plain non-volatile
     * fields.
     *
     * <p>Worse reach than the step-height case. It is declared by {@code WalkNodeEvaluator} itself, so
     * all six admitted families hit it, and it fires precisely when a mob has a target — hostile mobs
     * chasing a player, which is when async pathfinding is busiest. The corrupted value is the mob's
     * cached MAX_HEALTH.
     */
    private static final ThreadLocal<Integer> WORKER_MAX_FALL = new ThreadLocal<>();

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

    /**
     * Scope the prologue the main thread runs on a worker's behalf. Always paired in a finally.
     *
     * <p>Saves and restores rather than sets and clears. A prologue calls {@code onPathfindingStart}
     * on the live mob, which a mod may override, and an override that starts another mob navigating
     * would nest a second prologue inside the first. Clearing the flag on the inner exit would leave
     * the outer search building shared-cache-backed state again — the same silent failure this flag
     * exists to prevent, reachable only through third-party code and therefore only in the packs
     * least likely to report it.
     */
    public static boolean enterAsyncPrologue() {
        boolean previous = PREPARING_FOR_WORKER.get();
        PREPARING_FOR_WORKER.set(Boolean.TRUE);
        return previous;
    }

    public static void exitAsyncPrologue(boolean previous) {
        PREPARING_FOR_WORKER.set(previous);
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

    /**
     * Publish the step height this search must use, captured on the main thread at dispatch.
     *
     * <p>Call from the worker, inside the search, paired with {@link #clearWorkerStepHeight()} in a
     * finally. Pooled threads outlive a search, so a value left behind would be silently reused by
     * the next mob to run on that thread.
     */
    public static void setWorkerStepHeight(float stepHeight) {
        WORKER_STEP_HEIGHT.set(stepHeight);
    }

    public static void clearWorkerStepHeight() {
        WORKER_STEP_HEIGHT.remove();
    }

    public static void setWorkerMaxFallDistance(int maxFall) {
        WORKER_MAX_FALL.set(maxFall);
    }

    public static void clearWorkerMaxFallDistance() {
        WORKER_MAX_FALL.remove();
    }

    /** Null when this thread has none; the redirect then falls back to the live call. */
    public static Integer workerMaxFallDistance() {
        return WORKER_MAX_FALL.get();
    }

    /**
     * The captured step height, or {@code null} if this thread has none.
     *
     * <p>Null is deliberately distinguishable rather than defaulted. A worker running a search with
     * no captured value means the dispatch path changed and stopped supplying one; the redirect then
     * falls back to the live call, which is vanilla behaviour and the race this exists to avoid. That
     * is the correct direction to fail — a wrong step height is a permanent, silent, unreportable
     * behaviour change, whereas the race is at least the status quo ante.
     */
    public static Float workerStepHeight() {
        return WORKER_STEP_HEIGHT.get();
    }
}
