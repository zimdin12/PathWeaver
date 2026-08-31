package dev.pathweaver.brain;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What the brain-sink hook last did for a given mob, so a game test can ask instead of guess.
 *
 * <p>This exists because five reasoned hypotheses about one freeze were all wrong, two of them after
 * a code change that looked like it should have fixed it. At that point the cheap thing is no longer
 * another theory; it is a record of which branch actually ran.
 *
 * <p>OFF unless a test switches it on. It was written as an unconditional map write per decision,
 * which is a real cost on the one code path this mod exists to make cheaper: a village is hundreds of
 * brain mobs, each deciding every tick, and a ConcurrentHashMap merge per decision is not free. In a
 * performance mod that is exactly the wrong place to leave a debugging aid running. Enabled, it is
 * what caught a wrong attribution I had repeated four times; disabled, it is a predictable branch.
 */
public final class BrainSinkDiagnostics {
    private BrainSinkDiagnostics() {}

    /** Recording is off in production; tests turn it on. */
    private static volatile boolean enabled;

    public static void setEnabled(boolean on) {
        enabled = on;
        if (!on) clear();
    }

    private static final Map<Integer, String> LAST = new ConcurrentHashMap<>();
    private static final Map<Integer, Integer> START_CHECKS = new ConcurrentHashMap<>();
    private static final Map<Integer, Integer> TICK_HOOKS = new ConcurrentHashMap<>();

    public static void recordStartCheck(int entityId, String outcome) {
        if (!enabled) return;
        LAST.put(entityId, "sc:" + outcome);
        START_CHECKS.merge(entityId, 1, Integer::sum);
    }

    public static void recordTickHook(int entityId, String outcome) {
        if (!enabled) return;
        LAST.put(entityId, "tick:" + outcome);
        TICK_HOOKS.merge(entityId, 1, Integer::sum);
    }

    /** What the hook last decided for this mob, or {@code "never"} if it never ran. */
    public static String last(int entityId) {
        return LAST.getOrDefault(entityId, "never");
    }

    public static int startChecks(int entityId) { return START_CHECKS.getOrDefault(entityId, 0); }

    public static int tickHooks(int entityId) { return TICK_HOOKS.getOrDefault(entityId, 0); }

    private static final Map<Integer, java.util.List<String>> LIFECYCLE = new ConcurrentHashMap<>();

    /** Diagnostic: start/stop pairs for the behaviour, newest last, bounded. */
    public static void recordLifecycle(int entityId, String event) {
        if (!enabled) return;
        java.util.List<String> log =
            LIFECYCLE.computeIfAbsent(entityId, k -> java.util.Collections.synchronizedList(
                new java.util.ArrayList<>()));
        synchronized (log) {
            if (log.size() >= 12) log.remove(0);
            log.add(event);
        }
    }

    public static String lifecycle(int entityId) {
        java.util.List<String> log = LIFECYCLE.get(entityId);
        if (log == null) return "none";
        synchronized (log) { return String.join(">", log); }
    }

    public static void clear() {
        LIFECYCLE.clear();
        LAST.clear();
        START_CHECKS.clear();
        TICK_HOOKS.clear();
    }
}
