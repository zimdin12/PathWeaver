package dev.pathweaver.gate;

import net.minecraft.world.level.pathfinder.AmphibiousNodeEvaluator;
import net.minecraft.world.level.pathfinder.FlyNodeEvaluator;
import net.minecraft.world.level.pathfinder.SwimNodeEvaluator;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The land-registry half of the dispatch decision, which gates dispatch AND every reporting site.
 *
 * <p>It had no coverage at all when it was introduced. That is worth stating rather than quietly
 * fixing: the predicate exists because the startup banner and {@code /pathweaver status} were
 * announcing "all six families active" while five of six were refused every tick, and shipping its
 * replacement untested would have been the same class of mistake — a guard believed rather than
 * checked.
 */
class LandRegistryDispatchGateTest {

    @Test
    void aFamilyThatIsNotLandDerivedIsUnaffectedByTheLandRegistry() {
        assertFalse(WalkNodeEvaluator.class.isAssignableFrom(SwimNodeEvaluator.class),
            "fixture precondition: Swim must not be land-derived, or this test proves nothing");
        assertTrue(SafetyGate.landRegistryPermits(SwimNodeEvaluator.class, false, false),
            "swimming does not resolve block path types through WalkNodeEvaluator, so a closed "
                + "land registry must not hold it back");
    }

    @Test
    void aClosedLandRegistryStopsEveryLandDerivedFamilyNotJustWalk() {
        // The bug this replaced: testing for the exact Walk class covered the zombie and left the
        // other four dispatching against a registry that could have been populated. Assert the
        // inheritance that makes them land-derived, so a future version that changes it fails here
        // rather than silently narrowing the gate.
        for (Class<?> family : new Class<?>[] {
                WalkNodeEvaluator.class, FlyNodeEvaluator.class, AmphibiousNodeEvaluator.class }) {
            assertTrue(WalkNodeEvaluator.class.isAssignableFrom(family),
                family.getSimpleName() + " is expected to be land-derived");
            assertFalse(SafetyGate.landRegistryPermits(family, false, false),
                family.getSimpleName() + " must not dispatch while the land registry is unverified");
        }
    }

    @Test
    void theTwoPackagePrivateFamiliesAreLandDerivedToo() throws Exception {
        for (String name : new String[] {
                "net.minecraft.world.entity.animal.frog.Frog$FrogNodeEvaluator",
                "net.minecraft.world.entity.monster.creaking.Creaking$HomeNodeEvaluator" }) {
            Class<?> family = Class.forName(name);
            assertTrue(WalkNodeEvaluator.class.isAssignableFrom(family),
                name + " is expected to be land-derived");
            assertFalse(SafetyGate.landRegistryPermits(family, false, false),
                name + " must not dispatch while the land registry is unverified");
        }
    }

    @Test
    void aVerifiedAndEmptyLandRegistryLetsLandFamiliesThrough() {
        assertTrue(SafetyGate.landRegistryPermits(WalkNodeEvaluator.class, false, true),
            "with the latch open there is nothing left for this gate to refuse");
    }

    @Test
    void waivingTheScanWaivesThisGateToo() {
        // An operator who turned every check off must not still be silently refused, which is what
        // leaving this armed under UNSAFE would mean -- the setting would not do what it says.
        assertTrue(SafetyGate.landRegistryPermits(WalkNodeEvaluator.class, true, false),
            "bypassing the compatibility scan must waive the land-registry gate as well");
    }
}
