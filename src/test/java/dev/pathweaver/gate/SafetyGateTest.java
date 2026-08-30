package dev.pathweaver.gate;

import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import net.minecraft.world.level.pathfinder.SwimNodeEvaluator;
import net.minecraft.world.level.pathfinder.AmphibiousNodeEvaluator;
import net.minecraft.world.level.pathfinder.FlyNodeEvaluator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SafetyGateTest {
    /** Stand-in for a mod evaluator (like stormiespiders' AdvancedWalkNodeProcessor). */
    static class FakeSpiderEvaluator extends WalkNodeEvaluator {}

    @BeforeEach void clearDenialsBeforeTest() { SafetyGate.restoreDenialsForTesting(java.util.Set.of()); }
    @AfterEach void restoreFailClosedDefault() { SafetyGate.denyAllEligible(); }

    @Test void scanStartsWithEveryEligibleFamilyDenied() {
        SafetyGate.denyAllEligible();
        assertFalse(SafetyGate.isAllowed(WalkNodeEvaluator.class));
        assertFalse(SafetyGate.isAllowed(SwimNodeEvaluator.class));
    }

    @Test void vanillaWalkAllowed() {
        assertTrue(SafetyGate.isEvaluatorAllowed(WalkNodeEvaluator.class));
    }
    @Test void subclassDeniedByExactClass() {
        // instanceof would wrongly pass this; exact-class must deny it
        assertFalse(SafetyGate.isEvaluatorAllowed(FakeSpiderEvaluator.class));
    }
    @Test void unknownEvaluatorDenied() {
        assertFalse(SafetyGate.isEvaluatorAllowed(Object.class));
    }
    @Test void flyAllowedOnceItsOnlyLiveRandomnessReadIsThreadConfined() {
        // FlyNodeEvaluator reads Mob.getRandom() in exactly one place, start-node selection, and
        // FlyNodeEvaluatorMixin hands a worker its own source there. Vanilla picks that candidate
        // arbitrarily, so a different arbitrary pick is still a correct search.
        assertTrue(SafetyGate.isEvaluatorAllowed(SwimNodeEvaluator.class));
        assertTrue(SafetyGate.isEvaluatorAllowed(FlyNodeEvaluator.class));
    }
    @Test void amphibiousAllowedOnceItsMalusWritesRunOnTheMainThread() {
        // Its five Mob.setPathfindingMalus calls are all in prepare/done, never in the search, and
        // PathFinderMixin runs both of those on the main thread. The exclusion described the whole
        // evaluator as unsafe when only its two ends ever touched the mob.
        assertTrue(SafetyGate.isEvaluatorAllowed(AmphibiousNodeEvaluator.class));
    }
    @Test void foreignMixinDenialOverridesAllowlist() {
        assertTrue(SafetyGate.isAllowed(WalkNodeEvaluator.class));
        SafetyGate.denyForTesting(WalkNodeEvaluator.class);
        assertFalse(SafetyGate.isAllowed(WalkNodeEvaluator.class));
        assertTrue(SafetyGate.isEvaluatorAllowed(WalkNodeEvaluator.class)); // allowlist unchanged
    }

    /**
     * The enforced count is the closure, not the size of the set.
     *
     * <p>Denial is by {@code isAssignableFrom}. Denying {@code WalkNodeEvaluator} also denies Fly,
     * Amphibious, Frog and Creaking, so the raw set size reported "1" while five of six families
     * were refused on every tick. Both the startup log and /pathweaver status read this.
     */
    @org.junit.jupiter.api.Test
    void theEnforcedCountReportsTheClosureRatherThanTheSetSize() {
        SafetyGate.restoreDenialsForTesting(java.util.Set.of());
        org.junit.jupiter.api.Assertions.assertEquals(0, SafetyGate.scanEnforcedFamilyCount(),
            "nothing denied, nothing enforced");

        SafetyGate.denyForTesting(net.minecraft.world.level.pathfinder.WalkNodeEvaluator.class);
        int enforced = SafetyGate.scanEnforcedFamilyCount();
        org.junit.jupiter.api.Assertions.assertEquals(1, SafetyGate.denialCount(),
            "precondition: exactly one class is in the denial set");
        org.junit.jupiter.api.Assertions.assertTrue(enforced > 1,
            "denying WalkNodeEvaluator denies every family that extends it, so the enforced count "
                + "must exceed the set size. Reporting the size is the under-count this replaced.");

        // Every family it reports must genuinely be refused by the gate dispatch consults.
        for (Class<?> family : SafetyGate.allowlisted()) {
            if (net.minecraft.world.level.pathfinder.WalkNodeEvaluator.class
                    .isAssignableFrom(family)) {
                org.junit.jupiter.api.Assertions.assertFalse(SafetyGate.isAllowed(family),
                    family.getSimpleName() + " is counted as enforced and must actually be refused");
            }
        }
        SafetyGate.restoreDenialsForTesting(java.util.Set.of());
    }

    /**
     * Nothing outside this package can change what the scan denied.
     *
     * <p>The denial set was once a {@code public static final} mutable collection, so any class on a
     * user's classpath could clear it and waive every scan denial — including ones a FAILED scan
     * installed, which no tier is permitted to waive. Hiding the field was not enough on its own:
     * the accessors that replaced it were public too, which moved the hole rather than closing it.
     *
     * <p>Reflection over the public surface, not a source grep, because that is what a hostile mod
     * actually sees.
     */
    @org.junit.jupiter.api.Test
    void noPublicMutatorSurvivesOnTheShippedGate() {
        java.util.List<String> reachable = java.util.Arrays.stream(SafetyGate.class.getMethods())
            .filter(m -> m.getDeclaringClass() == SafetyGate.class)
            .filter(m -> {
                String n = m.getName().toLowerCase(java.util.Locale.ROOT);
                // "denials" does not contain "deny". resetRuntimeFailureDenials sailed through the
                // first version of this filter -- a public, void, no-argument method that wipes
                // every breaker trip, which is the mechanism COMPATIBILITY.md calls the thing that
                // replaced the scan as the safety net.
                return n.contains("deny") || n.contains("denial") || n.contains("restore")
                    || n.contains("undeny") || n.contains("reset") || n.contains("clear");
            })
            .filter(m -> m.getReturnType() == void.class)
            .map(java.lang.reflect.Method::getName)
            // No exclusions. Both names this used to exempt are package-private now, so
            // getMethods() never returns them, and an exclusion list that excludes nothing is a
            // place for a future public mutator to hide.

            .toList();
        org.junit.jupiter.api.Assertions.assertEquals(java.util.List.of(), reachable,
            "these mutate the denial set and are reachable from any class on the classpath. Tests "
                + "outside dev.pathweaver.gate must go through SafetyGateTestAccess, which lives in "
                + "a source set that is never packaged into the mod jar.");
    }
}
