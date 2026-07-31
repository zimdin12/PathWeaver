package dev.pathweaver.gate;

import dev.pathweaver.async.EvaluatorCloner;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import net.minecraft.world.level.pathfinder.AmphibiousNodeEvaluator;
import net.minecraft.world.level.pathfinder.FlyNodeEvaluator;
import net.minecraft.world.level.pathfinder.SwimNodeEvaluator;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;

/**
 * Decides whether a mob's A* search may run off-thread.
 *
 * <p>The rule is an exact-class allowlist of vanilla evaluators, never {@code instanceof}: a mod
 * evaluator that extends one of them — stormiespiders' {@code AdvancedWalkNodeProcessor} is the live
 * example — reads live world state during node evaluation, so {@code instanceof} would wrongly pass
 * it. Default-deny. The worker still consumes a read-only region view backed by live chunks and live
 * mob inputs, so async remains experimental.
 *
 * <p>The allowlist covers every concrete vanilla evaluator as of 26.1.2. The flying and amphibious
 * ones were excluded until 0.4.0, and the reason they no longer are is worth recording, because the
 * old exclusion described the problem inaccurately. It was never the search: the amphibious
 * evaluator's five {@code Mob.setPathfindingMalus} writes are all in {@code prepare} and {@code done},
 * and the flying evaluator's single {@code Mob.getRandom()} read is in start-node selection. The A*
 * loop between them only reads. {@code PathFinderMixin} now runs the prologue and epilogue on the
 * main thread and {@code FlyNodeEvaluatorMixin} gives a worker its own randomness, which removes the
 * hazard at its source rather than routing around it. The frog's and creaking's evaluators come along
 * with it: verified on 26.1.2, they override only {@code getStart} and {@code getPathType}, touch the
 * mob solely through {@code getBoundingBox}, and inherit their prologue from the classes above.
 *
 * <p>Two package-private evaluators are declared inside their mobs and so cannot be named in source.
 * They are resolved by name and simply absent if a future version moves them — the allowlist shrinks,
 * which fails in the safe direction.
 */
public final class SafetyGate {
    private static final String FROG_EVALUATOR =
        "net.minecraft.world.entity.animal.frog.Frog$FrogNodeEvaluator";
    private static final String CREAKING_EVALUATOR =
        "net.minecraft.world.entity.monster.creaking.Creaking$HomeNodeEvaluator";

    private static final Set<Class<?>> ALLOWED = allowlist();

    private static Set<Class<?>> allowlist() {
        Set<Class<?>> allowed = new LinkedHashSet<>(Set.of(
            WalkNodeEvaluator.class,
            SwimNodeEvaluator.class,
            FlyNodeEvaluator.class,
            AmphibiousNodeEvaluator.class));
        addIfPresent(allowed, FROG_EVALUATOR);
        addIfPresent(allowed, CREAKING_EVALUATOR);
        return Collections.unmodifiableSet(allowed);
    }

    private static void addIfPresent(Set<Class<?>> allowed, String className) {
        try {
            allowed.add(Class.forName(className, false, SafetyGate.class.getClassLoader()));
        } catch (ClassNotFoundException | LinkageError movedOrRenamed) {
            // Absent means that mob keeps pathing synchronously, which is the safe direction.
        }
    }

    /** Every evaluator class this build knows how to run off-thread, denials aside. */
    public static Set<Class<?>> allowlisted() {
        return ALLOWED;
    }

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

    /** Exact-class allowlist membership only. */
    public static boolean isEvaluatorAllowed(Class<?> evaluatorClass) {
        return ALLOWED.contains(evaluatorClass);
    }

    /**
     * True when a denial covers this evaluator, by inheritance rather than identity.
     *
     * <p>Every land evaluator extends {@code WalkNodeEvaluator} and executes its code, so a foreign
     * mixin into Walk modifies the search that a flying, amphibious, frog or creaking mob runs just
     * as much as a zombie's. Matching the denied set exactly would have denied the zombie and kept
     * dispatching the other four through the very code the scan objected to.
     */
    private static boolean isDenied(Class<?> evaluatorClass) {
        synchronized (deniedBySafety) {
            for (Class<?> denied : deniedBySafety) {
                if (denied.isAssignableFrom(evaluatorClass)) return true;
            }
        }
        return false;
    }

    /**
     * True when an evaluator subclass may run because the operator waived compatibility checking.
     *
     * <p>A mod subclassing an allowlisted evaluator is exactly the "another mod modified pathfinding"
     * case the tier exists to govern. At the tier that ignores the scan entirely, refusing it anyway
     * made the setting mean less than it says.
     */
    private static boolean isWaivableSubclass(Class<?> evaluatorClass) {
        for (Class<?> allowed : ALLOWED) {
            if (allowed.isAssignableFrom(evaluatorClass)) return true;
        }
        return false;
    }

    /**
     * Full gate: admitted by the allowlist or by an explicit waiver, not denied by a foreign mixin,
     * and actually rebuildable.
     *
     * <p>Rebuildability belongs here rather than at dispatch. It was previously discovered one layer
     * down, where a refusal became an abandoned dispatch that had already counted itself, and where
     * the in-game diagnostic reported a mob as eligible that could never run. A gate that answers
     * differently from what happens is not a gate.
     */
    public static boolean isAllowed(Class<?> evaluatorClass) {
        if (!isEvaluatorAllowed(evaluatorClass)
                && !(ActiveCompatibilityPolicy.bypassesScan() && isWaivableSubclass(evaluatorClass))) {
            return false;
        }
        return !isDenied(evaluatorClass) && EvaluatorCloner.canClone(evaluatorClass);
    }
}
