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

    @BeforeEach void clearDenialsBeforeTest() { SafetyGate.deniedBySafety.clear(); }
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
        SafetyGate.deniedBySafety.add(WalkNodeEvaluator.class);
        assertFalse(SafetyGate.isAllowed(WalkNodeEvaluator.class));
        assertTrue(SafetyGate.isEvaluatorAllowed(WalkNodeEvaluator.class)); // allowlist unchanged
    }
}
