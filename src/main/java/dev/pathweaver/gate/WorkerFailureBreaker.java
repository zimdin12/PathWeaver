package dev.pathweaver.gate;

import dev.pathweaver.config.PathWeaverConfig;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Switches a movement family off after its searches start throwing on a worker.
 *
 * <p><b>Why this exists.</b> PathWeaver's compatibility scan tries to decide in advance whether a
 * pack is safe. On a real modpack it cannot: it asks "did any mod touch this class?", the answer is
 * always yes, and the checked tier has left 0 of 187 mob types eligible since 0.3.0. So the shipped
 * default checks nothing. This is the other half of the answer — stop predicting, and notice.
 *
 * <p><b>What it is not.</b> A breaker sees throws. The failure a user actually fears is a silent torn
 * read that returns the wrong block and never throws, and nothing here catches that. On the reference
 * 221-jar pack a full validation run produces zero search failures, so on a healthy install this may
 * never fire. The half that earns its place on every install is {@link ModAttribution}: when something does
 * go wrong, the log names the family, the exception and — when it can — the mod.
 *
 * <p><b>Windowed, not cumulative.</b> A counter that never decays converges on a certain trip given
 * enough uptime, and this project's own Lithium audit describes a concurrent-resize exception as an
 * expected, contained event on the most widely installed performance mod there is. Three of those
 * spread over a fortnight is not an incompatibility; three in a minute is. A false trip is not a safe
 * no-op — being vanilla is the thing the user installed this mod to stop — so the window is what
 * makes the mechanism affordable. A cumulative ceiling still catches a genuine slow leak.
 *
 * <p><b>Threading.</b> {@link #recordSearchFailure} is called from the worker's catch block, so it may
 * run on many threads at once; {@link #setTick} is written by the main thread at end of tick. Counter
 * mutation is per-family and rare, so a monitor on the counter is right. Nothing here is on the
 * dispatch read path — {@code SafetyGate.isDenied} reads a volatile with no lock at all.
 */
public final class WorkerFailureBreaker {

    /**
     * Failures per family per session that trip regardless of the window.
     *
     * <p>Not configurable. The window is the user-facing knob; this is the backstop that keeps a
     * genuine slow leak from being invisible forever, and a second dial for it would be one more
     * thing to get wrong for no benefit anyone has asked for.
     */
    static final int CUMULATIVE_CEILING = 25;

    private static final Map<Class<?>, Counter> COUNTERS = new ConcurrentHashMap<>();

    /**
     * Server tick, published by the main thread and read by workers.
     *
     * <p>Wall-clock was the alternative and is worse: a tick is 50 ms only when the server is keeping
     * up, and a server that is NOT keeping up is exactly when this matters. A window measured in
     * ticks stays a window measured in the thing the user configured.
     */
    private static volatile long currentTick;

    /**
     * Bumped by {@link #reset()}, read either side of the trip.
     *
     * <p>{@code reset()} runs on the main thread at server start while workers from the previous
     * server may still be running — {@code pool.start()} uses {@code shutdownNow()}, which does not
     * wait. A worker already past the threshold check installs its trip into the NEW session's set,
     * and the new world starts with a family switched off and no log line to say why, because the
     * one-shot report already fired in the world before. That is verbatim the outcome the reset
     * exists to prevent.
     */
    private static volatile long sessionEpoch;

    private WorkerFailureBreaker() {}

    /** Which rule switched a family off. The log has to name the one that actually fired. */
    enum TripReason { WINDOW, CEILING }

    /** What one recorded failure decided, computed under the counter's own lock. */
    record Decision(boolean firstEver, TripReason reason, int count) {}

    /** Main thread, end of tick. */
    public static void setTick(long tick) {
        currentTick = tick;
    }

    /** Server start, not JVM start — see {@link SafetyGate#resetRuntimeFailureDenials()}. */
    public static void reset(long serverEpoch) {
        sessionEpoch = serverEpoch;
        COUNTERS.clear();
        currentTick = 0L;
        SafetyGate.resetRuntimeFailureDenials();
        ModAttribution.reset();
    }

    /**
     * What the breaker has counted for a family within the current window, or zero.
     *
     * <p>Public because the test that matters most for this feature lives in another package: the
     * pool-to-breaker wiring is what a bytecode-invisible mutation ({@code if (false)}) can sever
     * while every test in this package stays green.
     */
    public static int windowedCount(Class<?> family) {
        Counter counter = COUNTERS.get(family);
        return counter == null ? 0 : counter.windowed();
    }

    /**
     * Every failure this family has had this session, however far apart.
     *
     * <p>Distinct from {@link #windowedCount} and the distinction is not academic: the window resets
     * whenever it rolls, so a family that has failed twenty times over an afternoon -- five short of
     * the backstop -- reports a windowed count of one. The trip log tells operators that
     * {@code /pathweaver status} shows the running total, so the running total has to exist.
     */
    public static int cumulativeCount(Class<?> family) {
        Counter counter = COUNTERS.get(family);
        return counter == null ? 0 : counter.snapshotCount();
    }

    /**
     * Record that a search threw on a worker, and trip the family if it has thrown enough.
     *
     * <p>Every caller must still wrap this: it is invoked from a failure path, and the delivery side
     * of that path ({@code ResultInstaller.drain}) is a {@code try/finally} with no {@code catch},
     * inside a Fabric tick event. A mechanism that cannot record a failure must never turn that
     * failure into a server crash.
     *
     * @param evaluatorClass the evaluator the search was dispatched with; may be null
     * @return true if this call tripped the family, for callers that log the transition
     */
    /** The epoch failures are currently accepted for. */
    static long sessionEpochForTesting() {
        return sessionEpoch;
    }

    /**
     * Record a worker search failure.
     *
     * @param evaluatorClass the evaluator the search was dispatched with; may be null
     * @param dispatchEpoch the server epoch the REQUEST was created in, from its {@code RequestKey}
     * @return true if this call switched the family off
     */
    public static boolean recordSearchFailure(Class<?> evaluatorClass, Throwable failure,
                                              long dispatchEpoch) {
        return record(evaluatorClass, failure, dispatchEpoch);
    }

    private static boolean record(Class<?> evaluatorClass, Throwable failure, long dispatchEpoch) {
        // Stamped at DISPATCH, not here, and checked before anything is counted. Taking it here was
        // theatre: onServerStarting calls reset() BEFORE pool.start(), and pool.start() is what runs
        // the previous generation's shutdownNow() -- so every straggler failure arrived after the
        // increment, stamped the new session, and the guard could never fire for the case it was
        // written for. Three straggler throws switched a family off in a world that had had none.
        // Checked first, so a stale failure does not leave its count behind either: a fresh world was
        // reporting "N search failure(s) this session" for failures that belonged to the last one.
        long session = sessionEpoch;
        if (session != 0L && dispatchEpoch != 0L && dispatchEpoch != session) return false;
        // A VM error is not evidence that a worker read something it should not have. It is evidence
        // that the JVM is in trouble, it recurs in bursts, and on a 200-mod server it is the likeliest
        // throwable a worker will ever produce. Counting three OutOfMemoryErrors as an incompatibility
        // switches five movement families off for the session and prints a block naming whichever mods
        // happened to be on the frame -- a false trip, an invented culprit, and the real problem
        // unmentioned. The pool still logs it; this just refuses to draw a conclusion from it.
        if (failure instanceof VirtualMachineError) return false;
        Class<?> family = SafetyGate.allowlistedFamilyOf(evaluatorClass);
        if (family == null) return false;

        PathWeaverConfig config = PathWeaverConfig.get();
        int limit = config.workerFailureLimit;
        long window = config.workerFailureWindowTicks;

        // Re-read before touching shared state. reset() runs at onServerStarting BEFORE
        // pool.start() issues shutdownNow(), so previous-generation workers are provably still
        // running while it clears COUNTERS and ModAttribution's one-shot set. A straggler that read
        // the epoch above, was descheduled through reset(), and resumed here would insert a counter
        // into the NEW session's map and fire the new world's one-shot first-failure block for a
        // failure belonging to the old one -- leaving that world's genuine first failure unlogged,
        // which is the outcome reset() exists to produce. The trip was already re-validated; the
        // counting and the report were not.
        if (sessionEpoch != session) return false;
        Counter counter = COUNTERS.computeIfAbsent(family, ignored -> new Counter());
        // ONE acquisition of the counter's monitor for record-and-decide. It was three, so the count
        // that crossed the threshold was not the count that got reported: sixteen workers failing at
        // once with a limit of three reported seven -- neither the threshold nor the total. It also
        // left a window, narrow enough that I could not produce it, in which a worker that recorded
        // the limit-th failure could be descheduled past a window roll and then decline to trip.
        //
        // A limit of zero is the operator asking for 0.6.0's behaviour: failures still counted, still
        // logged, still attributed -- just never acted on. Decided AFTER recording so that turning
        // the breaker off does not also turn the diagnostics off, which is the trade the settings
        // screen describes and not a wider one.
        Decision decision = counter.recordAndDecide(currentTick, window, limit);
        // Checked again either side of the counter update: this is the one-shot that silences the
        // next world's first genuine failure if it burns here.
        if (sessionEpoch != session) return false;
        if (decision.firstEver()) ModAttribution.reportFirstFailure(family, failure);

        TripReason reason = decision.reason();
        if (reason == null) return false;
        // Re-read: a reset between here and the count above means this failure belongs to a server
        // that has already stopped, and its verdict must not follow the next one into the world.
        if (sessionEpoch != session) return false;
        if (!SafetyGate.tripRuntimeFailure(family)) return false;

        ModAttribution.reportTrip(family, failure, reason, decision.count(), limit, window);
        return true;
    }

    /** Per-family counts. Synchronized because two workers can fail in the same instant. */
    private static final class Counter {
        private long windowStartTick;
        private int inWindow;
        private int cumulative;
        private boolean everReported;

        /** Record one failure and decide what it means, under a single lock acquisition. */
        synchronized Decision recordAndDecide(long tick, long windowTicks, int limit) {
            // A clock that went backwards would freeze the window and quietly turn the breaker into
            // the cumulative counter it was designed not to be. Unreachable today -- getTickCount()
            // is monotonic per server and reset() zeroes the tick with the counters -- but this is one
            // line from being safe by construction rather than safe by circumstance.
            if (tick < windowStartTick) windowStartTick = tick;
            // A window of zero means "never decays", which is the strict behaviour for anyone who
            // wants it. Any other value restarts the window once it has elapsed.
            if (windowTicks > 0 && tick - windowStartTick > windowTicks) {
                windowStartTick = tick;
                inWindow = 0;
            }
            if (inWindow == 0 && windowTicks > 0) windowStartTick = tick;
            inWindow++;
            cumulative++;
            boolean first = !everReported;
            everReported = true;
            TripReason reason = limit <= 0 ? null : tripReason(limit);
            return new Decision(first, reason,
                reason == TripReason.CEILING ? cumulative : inWindow);
        }

        /** Which rule fired, or null. Called only from {@link #recordAndDecide}, under its lock. */
        private TripReason tripReason(int limit) {
            if (inWindow >= limit) return TripReason.WINDOW;
            // max(), not a bare ceiling. The ceiling exists to catch a leak too slow to fill the
            // window; it has no business overruling an operator who asked for a HIGHER limit. It did:
            // workerFailureLimit accepts up to 1000 and anything above 25 tripped at 25 anyway, with
            // nothing in the tooltip, the status line or the log admitting it. A config that silently
            // does something other than what it says is the failure this project's own runtime
            // warnings call worse than a config that does what you asked.
            if (cumulative >= Math.max(limit, CUMULATIVE_CEILING)) return TripReason.CEILING;
            return null;
        }

        synchronized int windowed() {
            return inWindow;
        }

        synchronized int snapshotCount() {
            return cumulative;
        }
    }

    /**
     * Run a reporting block, swallowing anything it throws.
     *
     * <p>NOT a one-shot guard, which is what this javadoc used to claim: the one-shot lives in
     * {@code ModAttribution.REPORTED_FIRST_FAILURE} and in {@code SafetyGate.tripRuntimeFailure}
     * returning the transition rather than the state. What this does is keep a logging failure from
     * becoming a second failure — the appender is third-party code, and this runs on a worker inside
     * a path whose delivery side has no catch of its own.
     */
    static void logSafely(Runnable emit) {
        try {
            emit.run();
        } catch (Throwable ignored) {
            // Logging is a third-party appender. Falling back to synchronous pathfinding stays the
            // outcome even if the log is compromised.
        }
    }
}
