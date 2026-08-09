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
 * 221-jar pack, 755 dispatches produced zero search failures, so on a healthy install this may never
 * fire. The half that earns its place on every install is {@link ModAttribution}: when something does
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

    private WorkerFailureBreaker() {}

    /** Main thread, end of tick. */
    public static void setTick(long tick) {
        currentTick = tick;
    }

    /** Server start, not JVM start — see {@link SafetyGate#resetRuntimeFailureDenials()}. */
    public static void reset() {
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
    public static boolean recordSearchFailure(Class<?> evaluatorClass, Throwable failure) {
        Class<?> family = SafetyGate.allowlistedFamilyOf(evaluatorClass);
        if (family == null) return false;

        PathWeaverConfig config = PathWeaverConfig.get();
        int limit = config.workerFailureLimit;
        long window = config.workerFailureWindowTicks;

        Counter counter = COUNTERS.computeIfAbsent(family, ignored -> new Counter());
        boolean firstEver = counter.recordAndCheckFirst(currentTick, window);
        if (firstEver) ModAttribution.reportFirstFailure(family, failure);

        // A limit of zero is the operator asking for 0.6.0's behaviour: failures still counted, still
        // logged, still attributed -- just never acted on. Checked AFTER recording so that turning
        // the breaker off does not also turn the diagnostics off, which is the trade the settings
        // screen describes and not a wider one.
        if (limit <= 0) return false;
        if (!counter.shouldTrip(limit)) return false;
        if (!SafetyGate.tripRuntimeFailure(family)) return false;

        ModAttribution.reportTrip(family, failure, counter.snapshotCount(), limit, window);
        return true;
    }

    /** Per-family counts. Synchronized because two workers can fail in the same instant. */
    private static final class Counter {
        private long windowStartTick;
        private int inWindow;
        private int cumulative;
        private boolean everReported;

        /** @return true when this is the first failure this family has ever recorded */
        synchronized boolean recordAndCheckFirst(long tick, long windowTicks) {
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
            return first;
        }

        synchronized boolean shouldTrip(int limit) {
            return inWindow >= limit || cumulative >= CUMULATIVE_CEILING;
        }

        synchronized int windowed() {
            return inWindow;
        }

        synchronized int snapshotCount() {
            return cumulative;
        }
    }

    /** One-shot guard so a storm of failures cannot become a storm of log blocks. */
    static void logSafely(Runnable emit) {
        try {
            emit.run();
        } catch (Throwable ignored) {
            // Logging is a third-party appender. Falling back to synchronous pathfinding stays the
            // outcome even if the log is compromised.
        }
    }
}
