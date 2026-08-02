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
        // Run on a real 222-mod pack this printed "uses WalkNodeEvaluator, which is not a vanilla
        // evaluator" -- about the most vanilla evaluator there is. The mob was refused because the
        // scan denied the family, which is a fact about the modlist, not about the zombie, and the
        // two send an operator to completely different places.
        SafetyGate.deniedBySafety.add(WalkNodeEvaluator.class);
        MobEligibility.Verdict verdict =
            MobEligibility.of(Zombie.class, WalkNodeEvaluator.class, false);
        assertFalse(verdict.eligible());
        assertTrue(verdict.reason().contains("compatibility scan denied"), verdict.reason());
        assertFalse(verdict.reason().contains("not a vanilla evaluator"), verdict.reason());
    }

    @Test
    void aGenuinelyForeignEvaluatorIsStillCalledThat() {
        MobEligibility.Verdict verdict =
            MobEligibility.of(Zombie.class, UnbuildableEvaluator.class, false);
        assertFalse(verdict.eligible());
        assertTrue(verdict.reason().contains("not a vanilla evaluator"), verdict.reason());
    }

    @Test
    void aNavigationWithoutAnEvaluatorSaysSo() {
        MobEligibility.Verdict verdict = MobEligibility.of(Zombie.class, null, false);
        assertFalse(verdict.eligible());
        assertTrue(verdict.reason().contains("without a node evaluator"), verdict.reason());
    }

    @Test
    void theThreeEvaluatorRefusalsDoNotReadAlike() {
        // Not vanilla, cannot be rebuilt, and denied by the scan are three different problems with
        // three different answers. Lumping them together is what produced the nonsense sentence.
        SafetyGate.deniedBySafety.add(WalkNodeEvaluator.class);
        String denied = MobEligibility.of(Zombie.class, WalkNodeEvaluator.class, false).reason();
        String foreign = MobEligibility.of(Zombie.class, UnbuildableEvaluator.class, false).reason();
        assertFalse(denied.equals(foreign), "distinct causes produced identical text");
        assertTrue(denied.contains("scan denied"), denied);
        assertTrue(foreign.contains("not a vanilla evaluator"), foreign);
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

    /**
     * The diagnostic must refuse a mod-supplied PathFinder, because dispatch does.
     *
     * <p>This is the second time a gate was added to dispatch and not to this class. The first was
     * the evaluator-subclass check; this one arrived with the PathFinder identity gate and made
     * `/pathweaver mobs` able to call a mob eligible that dispatch declines — which is worse than no
     * diagnostic, because the README points at this command as the way to reproduce the eligibility
     * numbers it publishes.
     */
    @Test
    void aModSuppliedPathFinderIsRefusedJustAsDispatchRefusesIt() {
        MobEligibility.Verdict vanilla = MobEligibility.of(
            Mob.class, WalkNodeEvaluator.class,
            net.minecraft.world.level.pathfinder.PathFinder.class, false);
        assertTrue(vanilla.eligible(), "a stock navigation must stay eligible: " + vanilla.reason());

        MobEligibility.Verdict modded = MobEligibility.of(
            Mob.class, WalkNodeEvaluator.class, ForeignPathFinder.class, false);
        assertFalse(modded.eligible(),
            "dispatch requires the exact vanilla PathFinder, so this diagnostic must too");
        assertTrue(modded.reason().contains("PathFinder"),
            "the reason must say what was wrong, not just refuse: " + modded.reason());
    }

    /** Null means "not inspected", which must not be reported as a refusal. */
    @Test
    void anUninspectedPathFinderIsNotTreatedAsARefusal() {
        MobEligibility.Verdict verdict =
            MobEligibility.of(Mob.class, WalkNodeEvaluator.class, null, false);
        assertTrue(verdict.eligible(),
            "a field this diagnostic could not read must not invent a refusal: " + verdict.reason());
    }

    private static final class ForeignPathFinder
            extends net.minecraft.world.level.pathfinder.PathFinder {
        ForeignPathFinder() {
            super(new WalkNodeEvaluator(), 1);
        }
    }

    /** No no-arg constructor and an argument matching no field, so it cannot be rebuilt. */
    private static final class UnbuildableEvaluator extends WalkNodeEvaluator {
        UnbuildableEvaluator(String unmatched) {
            if (unmatched == null) throw new IllegalArgumentException();
        }
    }
}
