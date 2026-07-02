package dev.pathweaver.gate;

import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import net.minecraft.world.level.pathfinder.SwimNodeEvaluator;
import net.minecraft.world.level.pathfinder.AmphibiousNodeEvaluator;
import net.minecraft.world.level.pathfinder.FlyNodeEvaluator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SafetyGateTest {
    /** Stand-in for a mod evaluator (like stormiespiders' AdvancedWalkNodeProcessor). */
    static class FakeSpiderEvaluator extends WalkNodeEvaluator {}

    @AfterEach void clearDenials() { SafetyGate.deniedBySafety.clear(); }

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
    @Test void swimAndAmphibiousAndFlyAllowed() {
        assertTrue(SafetyGate.isEvaluatorAllowed(SwimNodeEvaluator.class));
        assertTrue(SafetyGate.isEvaluatorAllowed(AmphibiousNodeEvaluator.class));
        assertTrue(SafetyGate.isEvaluatorAllowed(FlyNodeEvaluator.class));
    }
    @Test void foreignMixinDenialOverridesAllowlist() {
        assertTrue(SafetyGate.isAllowed(WalkNodeEvaluator.class));
        SafetyGate.deniedBySafety.add(WalkNodeEvaluator.class);
        assertFalse(SafetyGate.isAllowed(WalkNodeEvaluator.class));
        assertTrue(SafetyGate.isEvaluatorAllowed(WalkNodeEvaluator.class)); // allowlist unchanged
    }
}
