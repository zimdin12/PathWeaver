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
    /**
     * The thread type every PathWeaver worker runs on, holding its per-search state as plain fields.
     *
     * <p>WHY NOT THREADLOCALS. {@link #isWorker()}, {@link #workerStepHeight()} and
     * {@link #workerMaxFallDistance()} are called from inside the A* inner loop, once per node, in
     * EVERY search -- including the ones PathWeaver leaves on the server thread. They used to be
     * ThreadLocal reads. On the server thread of a large pack, whose ThreadLocalMap is crowded by
     * every mod, that read takes the slow probing path: profiled in a 317-mod client with an MCA
     * village, {@code ThreadLocalMap.getEntryAfterMiss} was 5.7 s of 20.6 s of synchronous search, and
     * villagers' point-of-interest searches cost 50-70% more per call than with no PathWeaver at all.
     * The mod was making every search it did not offload slower.
     *
     * <p>A type check on {@code Thread.currentThread()} costs the server thread one failed
     * {@code instanceof} per node, and a worker reads its own fields. Every field below is written and
     * read only by the worker thread that owns it, so none needs to be volatile.
     */
    public static final class Worker extends Thread {
        private boolean inSearch;
        private Float stepHeight;
        private Integer maxFallDistance;
        private net.minecraft.util.RandomSource random;

        public Worker(Runnable task, String name) {
            super(task, name);
        }
    }

    /** The calling thread as a worker, or null when it is any other thread. */
    private static Worker currentWorker() {
        return Thread.currentThread() instanceof Worker worker ? worker : null;
    }

    /** The calling thread as a worker, refusing any other thread: these calls belong to a search. */
    private static Worker requireWorker(String what) {
        Worker worker = currentWorker();
        if (worker == null) {
            throw new IllegalStateException(what + " called on " + Thread.currentThread().getName()
                + ", which is not a PathWeaver worker thread");
        }
        return worker;
    }

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
    // Held on Worker.random, created on first use by that worker.

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
     * <p>Same shape as {@link #workerRandom()}, and for the same reason: the search does not need the
     * live value, it needs <em>a</em> value fixed for the duration. Vanilla resolves it once per
     * search anyway, microseconds after the prologue, so a value captured at dispatch is what a
     * synchronous search would have observed.
     */
    // Held on Worker.stepHeight, boxed once at publication so the per-node read allocates nothing.

    /**
     * The mob's max fall distance, resolved on the main thread at dispatch.
     *
     * <p>The same hazard as the step height reached by a different route, and it was
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
     *
     * <p>Precisely: that describes {@code Mob.getMaxFallDistance()}. {@code Creeper} overrides it and
     * reads only {@code getHealth()} ({@code SynchedEntityData}, no attribute). The redirect is on the
     * call site rather than the implementation, so it covers both regardless.
     */
    // Held on Worker.maxFallDistance.

    private PathWeaverThread() {}

    /** True only while a PathWeaver worker is executing a search Callable. */
    public static boolean isWorker() {
        Worker worker = currentWorker();
        return worker != null && worker.inSearch;
    }

    /**
     * True when the search this code is serving will run off the main thread — either because a
     * worker is running it now, or because the main thread is preparing it for one.
     *
     * <p>This is the condition every shared-state isolation decision must use.
     */
    public static boolean searchRunsOffThread() {
        return isWorker() || PREPARING_FOR_WORKER.get();
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
        Worker worker = requireWorker("workerRandom");
        if (worker.random == null) worker.random = net.minecraft.util.RandomSource.create();
        return worker.random;
    }

    /** Set by {@link PathWorkerPool} at the very start of a worker search. */
    public static void enterWorker() {
        requireWorker("enterWorker").inSearch = true;
    }

    /** Cleared by {@link PathWorkerPool} in a finally after the search, so pooled threads reset cleanly. */
    public static void exitWorker() {
        Worker worker = currentWorker();
        if (worker != null) worker.inSearch = false;
    }

    /**
     * Publish the step height this search must use, captured on the main thread at dispatch.
     *
     * <p>Call from the worker, inside the search, paired with {@link #clearWorkerStepHeight()} in a
     * finally. Pooled threads outlive a search, so a value left behind would be silently reused by
     * the next mob to run on that thread.
     */
    public static void setWorkerStepHeight(float stepHeight) {
        requireWorker("setWorkerStepHeight").stepHeight = stepHeight;
    }

    public static void clearWorkerStepHeight() {
        Worker worker = currentWorker();
        if (worker != null) worker.stepHeight = null;
    }

    /**
     * Publish the max fall distance this search must use, captured on the main thread at dispatch.
     *
     * <p>Same contract as {@link #setWorkerStepHeight(float)} and it matters for the same reason:
     * call from the worker, inside the search, paired with {@link #clearWorkerMaxFallDistance()} in a
     * finally. Pooled threads outlive a search, so a value left behind would be silently reused by
     * the next mob to run on that thread — a wrong fall tolerance for an unrelated mob, with nothing
     * in any log to connect it to pathfinding.
     */
    public static void setWorkerMaxFallDistance(int maxFall) {
        requireWorker("setWorkerMaxFallDistance").maxFallDistance = maxFall;
    }

    public static void clearWorkerMaxFallDistance() {
        Worker worker = currentWorker();
        if (worker != null) worker.maxFallDistance = null;
    }

    /** Null when this thread has none; the redirect then falls back to the live call. */
    public static Integer workerMaxFallDistance() {
        Worker worker = currentWorker();
        return worker == null ? null : worker.maxFallDistance;
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
        Worker worker = currentWorker();
        return worker == null ? null : worker.stepHeight;
    }
}
