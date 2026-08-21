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
     * A name for a class that always has one.
     *
     * <p>{@code getSimpleName()} returns the empty string for an anonymous class, and a real pack
     * produced exactly that: "navigates with , a PathFinder subclass", in the line whose only
     * job is to name what is responsible.
     */
    private static String describe(Class<?> type) {
        String simple = type.getSimpleName();
        return simple.isEmpty() ? type.getName() : simple;
    }

    /**
     * Whether Fabric's land path-type registry is currently holding walk-derived families back.
     *
     * <p>An enum rather than the obvious second {@code boolean}, because the obvious version was
     * reviewed and broken in two ways within one round. {@code verdictFor(mob, moddedAllowed, false)}
     * compiled and the whole suite stayed green, and so did
     * {@code verdictFor(mob, landRegistryBlocksWalkFamilies(), moddedAllowed)} — two same-typed
     * booleans side by side, with nothing but argument position telling them apart. A distinct type
     * makes the swap a compile error, and makes the hard-coded constant a {@code GETSTATIC} of a named
     * field that a bytecode contract can point at exactly, instead of an {@code ICONST_0} that a test
     * has to guess the meaning of.
     */
    public enum LandRegistry {
        /** Verified empty, or the tier waives the check. Land families dispatch. */
        PERMITS,
        /** Not verified empty, so dispatch refuses every walk-derived family on every tick. */
        BLOCKS_LAND_FAMILIES;

        /**
         * The live answer. The only route a production call site may use.
         *
         * <p>Public so {@code LandRegistryDispatchGateTest} — which owns the only legal way to move
         * the latch — can pin it. It was package-private and called from exactly one place and by no
         * test, so {@code return PERMITS} here reproduced the bug the call-site contract had just
         * been hardened against, one frame lower down.
         */
        public static LandRegistry live() {
            return SafetyGate.landRegistryBlocksWalkFamilies() ? BLOCKS_LAND_FAMILIES : PERMITS;
        }

        public boolean blocksLandFamilies() {
            return this == BLOCKS_LAND_FAMILIES;
        }
    }

    /**
     * @param evaluatorClass the evaluator the mob's navigation really holds, or null if it has none
     */
    public static Verdict of(Class<?> mobClass, Class<?> evaluatorClass, boolean moddedAllowed) {
        return of(mobClass, evaluatorClass, null, moddedAllowed, LandRegistry.PERMITS);
    }

    public static Verdict of(Class<?> mobClass, Class<?> evaluatorClass, Class<?> pathFinderClass,
                             boolean moddedAllowed) {
        return of(mobClass, evaluatorClass, pathFinderClass, moddedAllowed, LandRegistry.PERMITS);
    }

    /**
     * @param pathFinderClass the PathFinder the navigation really holds, or null when not inspected
     */
    public static Verdict of(Class<?> mobClass, Class<?> evaluatorClass, Class<?> pathFinderClass,
                             boolean moddedAllowed, LandRegistry landRegistry) {
        boolean originOk = MobOriginGate.isAllowed(mobClass, moddedAllowed);
        if (evaluatorClass == null) {
            return new Verdict(false, "navigates without a node evaluator");
        }
        // Dispatch builds its own vanilla PathFinder and so refuses any subclass outright. Omitting
        // that here let this diagnostic call a mob eligible that dispatch would decline -- exactly
        // the drift the class comment says cannot be allowed to happen, reintroduced by a gate added
        // later. The rule is not restated: the same exact-class comparison is used.
        //
        // "mod-supplied" is NOT what this says, and used to be. Naming the class exposed the reason:
        // on a real pack the third refusal was `Warden$1$1`, an anonymous PathFinder that VANILLA
        // constructs inside the warden's navigation. Blaming a mod for a vanilla class is the sort of
        // invented cause the rest of this release exists to remove.
        if (pathFinderClass == PathWeaverCommand.NULL_PATH_FINDER) {
            return new Verdict(false, "has no PathFinder yet, so dispatch declines and this mob "
                + "paths on the server thread");
        }
        if (pathFinderClass != null
                && pathFinderClass != net.minecraft.world.level.pathfinder.PathFinder.class) {
            return new Verdict(false, "navigates with " + describe(pathFinderClass)
                + ", a PathFinder subclass rather than the vanilla one");
        }
        // The land registry is part of the dispatch decision, and asking isAllowed alone omits it.
        // That was harmless while /pathweaver mobs returned early in the blocked state; round seven
        // removed the early return so swim mobs would stop being under-reported, and in doing so
        // started printing this table in exactly the state where the omission is wrong -- every land
        // family reading "eligible" while dispatch refuses all five on every tick. The class comment
        // above says a diagnostic must ask the gate that actually decides; this is that gate.
        // Asked BEFORE isAllowed's other reasons, because isAllowed answers only true/false and every
        // downstream explanation assumes the answer came from the scan or the clone check. A family
        // switched off at runtime is neither, and reporting it as either sends the operator to edit
        // trustedMods over a problem no setting will fix.
        if (SafetyGate.isDeniedByRuntimeFailure(evaluatorClass)) {
            return new Verdict(false, "switched off after its searches threw on a worker thread "
                + "(see the PathWeaver block in the log; restart to re-arm)");
        }
        boolean evaluatorOk = SafetyGate.isAllowed(evaluatorClass);
        if (evaluatorOk && landRegistry.blocksLandFamilies()
                && SafetyGate.isLandDerived(evaluatorClass)) {
            return new Verdict(false, "held back by Fabric's land path-type registry");
        }
        if (evaluatorOk && originOk) return new Verdict(true, ELIGIBLE);

        String origin = originOk ? null : "added by a mod";
        String evaluator = evaluatorOk ? null : evaluatorReason(evaluatorClass);
        if (origin != null && evaluator != null) return new Verdict(false, origin + ", and " + evaluator);
        if (origin != null) {
            return new Verdict(false, origin + " (enable \"Speed up mod-added mobs (unsafe)\")");
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
