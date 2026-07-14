package dev.pathweaver.gate;

import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import net.minecraft.world.level.pathfinder.SwimNodeEvaluator;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Decides whether a mob's A* search may run off-thread. The rule: the mob's NodeEvaluator must be
 * EXACTLY one of the temporarily eligible vanilla evaluator classes. The worker still consumes a
 * read-only region view backed by live chunks and live mob inputs, so async remains experimental and
 * opt-in while v0.2 replaces those inputs with immutable copies.
 *
 * Exact-class ({@code getClass() ==}), never {@code instanceof}: a mod evaluator that
 * {@code extends WalkNodeEvaluator} (e.g. stormiespiders' AdvancedWalkNodeProcessor) reads live
 * world state during node evaluation, so {@code instanceof} would wrongly pass it. Default-deny.
 *
 * {@code AmphibiousNodeEvaluator} is deliberately EXCLUDED: verified on 26.1.2, its {@code prepare}/
 * {@code done} save-and-restore the live mob's WATER/WATER_BORDER pathfinding malus via
 * {@code mob.setPathfindingMalus(...)} — a WRITE to live entity state that would race off-thread (and
 * can't be reproduced faithfully off-thread anyway). It stays synchronous. {@code SwimNodeEvaluator}
 * is eligible for the experimental path: its prepare/done only touch evaluator fields.
 * {@code FlyNodeEvaluator} is excluded because start-node selection consumes the live mob RNG from
 * the worker thread.
 */
public final class SafetyGate {
    private static final Set<Class<?>> ALLOWED = Set.of(
        WalkNodeEvaluator.class,
        SwimNodeEvaluator.class
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
