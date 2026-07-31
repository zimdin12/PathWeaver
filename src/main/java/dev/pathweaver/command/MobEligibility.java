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
        boolean originOk = MobOriginGate.isAllowed(mobClass, moddedAllowed);
        boolean evaluatorOk = evaluatorClass != null && SafetyGate.isAllowed(evaluatorClass);

        if (evaluatorOk && originOk) return new Verdict(true, ELIGIBLE);
        if (!originOk && !evaluatorOk) {
            return new Verdict(false, "added by a mod, and its evaluator is not a vanilla one");
        }
        if (!originOk) {
            return new Verdict(false, "added by a mod (enable \"Also speed up mobs added by mods\")");
        }
        if (evaluatorClass == null) {
            return new Verdict(false, "navigates without a node evaluator");
        }
        // Both remaining cases are an evaluator refusal, and they are worth telling apart: one is a
        // mod having replaced pathfinding, the other is this mod being unable to rebuild it.
        if (!EvaluatorCloner.canClone(evaluatorClass)) {
            return new Verdict(false, "uses " + evaluatorClass.getSimpleName()
                + ", which has no constructor shape we can rebuild");
        }
        return new Verdict(false, "uses " + evaluatorClass.getSimpleName()
            + ", which is not a vanilla evaluator or is denied by the compatibility scan");
    }
}
