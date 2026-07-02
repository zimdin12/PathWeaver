package dev.pathweaver.gate;

import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import net.minecraft.world.level.pathfinder.SwimNodeEvaluator;
import net.minecraft.world.level.pathfinder.AmphibiousNodeEvaluator;
import net.minecraft.world.level.pathfinder.FlyNodeEvaluator;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Decides whether a mob's A* search may run off-thread. The rule: the mob's NodeEvaluator must be
 * EXACTLY one of the vanilla evaluator classes we have audited to read only the read-only snapshot.
 *
 * Exact-class ({@code getClass() ==}), never {@code instanceof}: a mod evaluator that
 * {@code extends WalkNodeEvaluator} (e.g. stormiespiders' AdvancedWalkNodeProcessor) reads live
 * world state during node evaluation, so {@code instanceof} would wrongly pass it. Default-deny.
 */
public final class SafetyGate {
    private static final Set<Class<?>> ALLOWED = Set.of(
        WalkNodeEvaluator.class,
        SwimNodeEvaluator.class,
        AmphibiousNodeEvaluator.class,
        FlyNodeEvaluator.class
    );

    /**
     * Allowlisted vanilla classes that another jar mixes into (populated at startup by
     * {@link ForeignMixinScanner}). A mixin keeps the class identity {@code WalkNodeEvaluator},
     * so the allowlist alone cannot see it — this set is the second line of defence.
     */
    public static final Set<Class<?>> deniedBySafety =
        Collections.synchronizedSet(new HashSet<>());

    private SafetyGate() {}

    /** Exact-class allowlist membership only. */
    public static boolean isEvaluatorAllowed(Class<?> evaluatorClass) {
        return ALLOWED.contains(evaluatorClass);
    }

    /** Full gate: allowlisted AND not force-denied by a foreign mixin. */
    public static boolean isAllowed(Class<?> evaluatorClass) {
        return isEvaluatorAllowed(evaluatorClass)
            && !deniedBySafety.contains(evaluatorClass);
    }
}
