package dev.pathweaver.gate;

import net.minecraft.world.level.pathfinder.AmphibiousNodeEvaluator;
import net.minecraft.world.level.pathfinder.FlyNodeEvaluator;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import net.minecraft.world.level.pathfinder.SwimNodeEvaluator;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Decides whether a mob's A* search may run off-thread. The rule: the mob's NodeEvaluator must be
 * EXACTLY one of the temporarily eligible vanilla evaluator classes. The worker still consumes a
 * read-only region view backed by live chunks and live mob inputs, so async remains experimental even
 * though v0.2.1 enables it by default. The fail-closed scanner may force eligible families sync.
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
        Collections.synchronizedSet(new HashSet<>(ALLOWED));

    private SafetyGate() {}

    /** Fail closed before and during compatibility discovery. */
    static void denyAllEligible() {
        replaceDenials(ALLOWED);
    }

    static void replaceDenials(Set<Class<?>> denied) {
        synchronized (deniedBySafety) {
            deniedBySafety.clear();
            deniedBySafety.addAll(denied);
        }
    }

    /**
     * Vanilla evaluators that write to the mob itself, and every subclass of them.
     *
     * <p>Excluded at <em>every</em> tier, including the one that waives all compatibility checking,
     * and the distinction is worth stating precisely. The tier decides how much risk to accept from
     * <em>other mods'</em> code. These two are vanilla, and they are not a risk -- they are a
     * certainty. {@code AmphibiousNodeEvaluator} calls {@code Mob.setPathfindingMalus} five times,
     * saving and restoring live entity state around a search; {@code FlyNodeEvaluator} calls
     * {@code Mob.getRandom} and advances the mob's RNG during start-node selection. Both mutate the
     * mob from a worker thread on every single search, with zero mods installed. No compatibility
     * setting can make that safe, so exposing it behind one would ship a mode that is broken by
     * construction. Fixing it means resolving that state on the main thread before dispatch, the way
     * step height already is -- a feature, not a toggle.
     *
     * <p>By assignability, not exact class: {@code Frog$FrogNodeEvaluator} extends the amphibious
     * one and inherits the writes.
     */
    private static final Set<Class<?>> MUTATES_THE_MOB = Set.of(
        AmphibiousNodeEvaluator.class,
        FlyNodeEvaluator.class
    );

    /** Exact-class allowlist membership only. */
    public static boolean isEvaluatorAllowed(Class<?> evaluatorClass) {
        return ALLOWED.contains(evaluatorClass);
    }

    /**
     * True when an evaluator subclass may run because the operator waived compatibility checking.
     *
     * <p>A mod subclassing {@code WalkNodeEvaluator} -- stormiespiders' {@code
     * AdvancedWalkNodeProcessor} is the live example, and it is why spiders path synchronously -- is
     * exactly the "another mod modified pathfinding" case the tier exists to govern. At the tier
     * that ignores the scan entirely, refusing it anyway made the setting mean less than it says.
     *
     * <p>Both vanilla exclusions also extend {@code WalkNodeEvaluator}, so admitting subclasses
     * without {@link #MUTATES_THE_MOB} would silently admit precisely the two that are unsafe.
     */
    private static boolean isWaivableSubclass(Class<?> evaluatorClass) {
        for (Class<?> unsafe : MUTATES_THE_MOB) {
            if (unsafe.isAssignableFrom(evaluatorClass)) return false;
        }
        for (Class<?> allowed : ALLOWED) {
            if (allowed.isAssignableFrom(evaluatorClass)) return true;
        }
        return false;
    }

    /**
     * Full gate: allowlisted AND not force-denied by a foreign mixin.
     *
     * <p>Where the operator has waived compatibility checking, a third-party subclass of an
     * allowlisted evaluator is admitted too -- see {@link #isWaivableSubclass}.
     */
    public static boolean isAllowed(Class<?> evaluatorClass) {
        if (isEvaluatorAllowed(evaluatorClass)) {
            return !deniedBySafety.contains(evaluatorClass);
        }
        return ActiveCompatibilityPolicy.bypassesScan() && isWaivableSubclass(evaluatorClass);
    }
}
