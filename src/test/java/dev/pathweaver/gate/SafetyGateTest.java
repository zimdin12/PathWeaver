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
    @Test void swimAllowedButFlyDeniedBecauseFlyConsumesLiveMobRngOffThread() {
        assertTrue(SafetyGate.isEvaluatorAllowed(SwimNodeEvaluator.class));
        assertFalse(SafetyGate.isEvaluatorAllowed(FlyNodeEvaluator.class));
    }
    @Test void amphibiousDeniedBecauseItMutatesLiveMobMalusOffThread() {
        // Amphibious prepare/done call mob.setPathfindingMalus(...) - a live-entity WRITE that would
        // race off-thread. It is intentionally excluded from the allowlist and stays synchronous.
        assertFalse(SafetyGate.isEvaluatorAllowed(AmphibiousNodeEvaluator.class));
    }
    @Test void foreignMixinDenialOverridesAllowlist() {
        assertTrue(SafetyGate.isAllowed(WalkNodeEvaluator.class));
        SafetyGate.deniedBySafety.add(WalkNodeEvaluator.class);
        assertFalse(SafetyGate.isAllowed(WalkNodeEvaluator.class));
        assertTrue(SafetyGate.isEvaluatorAllowed(WalkNodeEvaluator.class)); // allowlist unchanged
    }
}
