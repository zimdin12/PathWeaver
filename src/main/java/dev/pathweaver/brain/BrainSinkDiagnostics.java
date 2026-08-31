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
 * <p>Deliberately tiny and deliberately not on the hot path's critical work: one map write per
 * decision, no allocation beyond the interned strings the call sites pass. It is read only by tests
 * and by nothing that ships a decision.
 */
public final class BrainSinkDiagnostics {
    private BrainSinkDiagnostics() {}

    private static final Map<Integer, String> LAST = new ConcurrentHashMap<>();
    private static final Map<Integer, Integer> START_CHECKS = new ConcurrentHashMap<>();
    private static final Map<Integer, Integer> TICK_HOOKS = new ConcurrentHashMap<>();

    public static void recordStartCheck(int entityId, String outcome) {
        LAST.put(entityId, "sc:" + outcome);
        START_CHECKS.merge(entityId, 1, Integer::sum);
    }

    public static void recordTickHook(int entityId, String outcome) {
        LAST.put(entityId, "tick:" + outcome);
        TICK_HOOKS.merge(entityId, 1, Integer::sum);
    }

    /** What the hook last decided for this mob, or {@code "never"} if it never ran. */
    public static String last(int entityId) {
        return LAST.getOrDefault(entityId, "never");
    }

    public static int startChecks(int entityId) { return START_CHECKS.getOrDefault(entityId, 0); }

    public static int tickHooks(int entityId) { return TICK_HOOKS.getOrDefault(entityId, 0); }

    public static void clear() {
        LAST.clear();
        START_CHECKS.clear();
        TICK_HOOKS.clear();
    }
}
