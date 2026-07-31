package dev.pathweaver.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.pathweaver.gate.SafetyGate;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.level.pathfinder.SwimNodeEvaluator;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The in-game diagnostic's rules, tested without a server.
 *
 * <p>Worth testing at all because a diagnostic that disagrees with the dispatch path is worse than
 * no diagnostic: it was the absence of one that let a widened gate sit inert for a release without
 * anybody noticing. These assertions are about the explanation, not the rules — each rule is
 * delegated to the gate that really decides.
 */
class MobEligibilityTest {

    private java.util.Set<Class<?>> savedDenials;

    @BeforeEach
    void clearDenials() {
        synchronized (SafetyGate.deniedBySafety) {
            savedDenials = java.util.Set.copyOf(SafetyGate.deniedBySafety);
            SafetyGate.deniedBySafety.clear();
        }
    }

    @AfterEach
    void restoreDenials() {
        synchronized (SafetyGate.deniedBySafety) {
            SafetyGate.deniedBySafety.clear();
            SafetyGate.deniedBySafety.addAll(savedDenials);
        }
    }

    @Test
    void aVanillaMobWithAVanillaEvaluatorIsEligible() {
        MobEligibility.Verdict verdict =
            MobEligibility.of(Zombie.class, WalkNodeEvaluator.class, false);
        assertTrue(verdict.eligible());
        assertEquals(MobEligibility.ELIGIBLE, verdict.reason());
    }

    @Test
    void aDeniedEvaluatorExplainsItselfAsDeniedRatherThanAsNotVanilla() {
        SafetyGate.deniedBySafety.add(WalkNodeEvaluator.class);
        MobEligibility.Verdict verdict =
            MobEligibility.of(Zombie.class, WalkNodeEvaluator.class, false);
        assertFalse(verdict.eligible());
        assertTrue(verdict.reason().contains("compatibility scan"), verdict.reason());
    }

    @Test
    void aNavigationWithoutAnEvaluatorSaysSo() {
        MobEligibility.Verdict verdict = MobEligibility.of(Zombie.class, null, false);
        assertFalse(verdict.eligible());
        assertTrue(verdict.reason().contains("without a node evaluator"), verdict.reason());
    }

    @Test
    void anUnrebuildableEvaluatorIsDistinguishedFromANonVanillaOne() {
        MobEligibility.Verdict verdict =
            MobEligibility.of(Zombie.class, UnbuildableEvaluator.class, true);
        assertFalse(verdict.eligible());
        // The operator can act on one of these and not the other, so they must not read alike.
        assertTrue(verdict.reason().contains("no constructor shape"), verdict.reason());
    }

    @Test
    void everyVerdictReadsAsACompletionOfThisMobType() {
        for (Class<?> evaluator : new Class<?>[] {
                WalkNodeEvaluator.class, SwimNodeEvaluator.class, UnbuildableEvaluator.class, null}) {
            for (boolean modded : new boolean[] {true, false}) {
                MobEligibility.Verdict verdict = MobEligibility.of(Mob.class, evaluator, modded);
                assertFalse(verdict.reason().isBlank());
                assertFalse(verdict.reason().endsWith("."), "grouped labels do not end in a period");
            }
        }
    }

    /** No no-arg constructor and an argument matching no field, so it cannot be rebuilt. */
    private static final class UnbuildableEvaluator extends WalkNodeEvaluator {
        UnbuildableEvaluator(String unmatched) {
            if (unmatched == null) throw new IllegalArgumentException();
        }
    }
}
