package dev.pathweaver.command;

import dev.pathweaver.async.EvaluatorCloner;
import dev.pathweaver.gate.MobOriginGate;
import dev.pathweaver.gate.SafetyGate;

/**
 * Why one mob type can or cannot have its pathfinding run off-thread.
 *
 * <p>Separated from the command so the answer can be tested without a running server. The rules
 * themselves are never restated here — each one asks the gate that actually decides at dispatch, so a
 * diagnostic cannot drift into disagreeing with the code it describes. That has already happened
 * once: the gate admitted evaluator subclasses while a second check rejected them, and the only
 * reason it went unnoticed is that nothing reported the disagreement.
 */
public final class MobEligibility {
    private MobEligibility() {}

    /**
     * @param eligible whether a search for this mob would be dispatched off-thread
     * @param reason   a phrase completing "this mob type ...", suitable for grouping identical cases
     */
    public record Verdict(boolean eligible, String reason) {}

    public static final String ELIGIBLE = "eligible";

    /**
     * @param evaluatorClass the evaluator the mob's navigation really holds, or null if it has none
     */
    public static Verdict of(Class<?> mobClass, Class<?> evaluatorClass, boolean moddedAllowed) {
        return of(mobClass, evaluatorClass, null, moddedAllowed);
    }

    /**
     * @param pathFinderClass the PathFinder the navigation really holds, or null when not inspected
     */
    public static Verdict of(Class<?> mobClass, Class<?> evaluatorClass, Class<?> pathFinderClass,
                             boolean moddedAllowed) {
        boolean originOk = MobOriginGate.isAllowed(mobClass, moddedAllowed);
        if (evaluatorClass == null) {
            return new Verdict(false, "navigates without a node evaluator");
        }
        // Dispatch builds its own vanilla PathFinder and so refuses a mod-supplied one outright.
        // Omitting that here let this diagnostic call a mob eligible that dispatch would decline --
        // exactly the drift the class comment says cannot be allowed to happen, reintroduced by a
        // gate added later. The rule is not restated: the same exact-class comparison is used.
        if (pathFinderClass != null
                && pathFinderClass != net.minecraft.world.level.pathfinder.PathFinder.class) {
            return new Verdict(false, "navigates with " + pathFinderClass.getSimpleName()
                + ", a mod-supplied PathFinder rather than the vanilla one");
        }
        boolean evaluatorOk = SafetyGate.isAllowed(evaluatorClass);
        if (evaluatorOk && originOk) return new Verdict(true, ELIGIBLE);

        String origin = originOk ? null : "added by a mod";
        String evaluator = evaluatorOk ? null : evaluatorReason(evaluatorClass);
        if (origin != null && evaluator != null) return new Verdict(false, origin + ", and " + evaluator);
        if (origin != null) {
            return new Verdict(false, origin + " (enable \"Also speed up mobs added by mods\")");
        }
        return new Verdict(false, evaluator);
    }

    /**
     * Why one evaluator was refused, told apart rather than lumped together.
     *
     * <p>These send an operator to three different places, and running this on a real pack produced
     * the sentence "uses WalkNodeEvaluator, which is not a vanilla evaluator" -- about the most
     * vanilla evaluator there is. The mob was refused because six mods had mixed into pathfinding
     * and the scan denied the family, which is a fact about their modlist, not about the zombie.
     */
    private static String evaluatorReason(Class<?> evaluatorClass) {
        String name = evaluatorClass.getSimpleName();
        if (!SafetyGate.isEvaluatorAllowed(evaluatorClass)) {
            return "uses " + name + ", which is not a vanilla evaluator";
        }
        if (!EvaluatorCloner.canClone(evaluatorClass)) {
            return "uses " + name + ", which has no constructor shape we can rebuild";
        }
        return "uses " + name + ", whose family the compatibility scan denied";
    }
}
