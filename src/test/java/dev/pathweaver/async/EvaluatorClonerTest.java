package dev.pathweaver.async;

import net.minecraft.world.level.pathfinder.FlyNodeEvaluator;
import net.minecraft.world.level.pathfinder.NodeEvaluator;
import net.minecraft.world.level.pathfinder.SwimNodeEvaluator;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the narrow cloner contract: a distinct evaluator of the same class receives the supported
 * flags/constructor option. It does not assert async/sync path equivalence.
 */
class EvaluatorClonerTest {
    @BeforeAll static void boot() {
        // WalkNodeEvaluator construction is registry-free, but bootstrap keeps this robust.
        try { net.minecraft.server.Bootstrap.bootStrap(); } catch (Throwable ignored) {}
    }

    @Test void cloneIsDistinctSameClassWithCopiedFlags() throws Exception {
        WalkNodeEvaluator src = new WalkNodeEvaluator();
        src.setCanPassDoors(true);
        src.setCanOpenDoors(true);
        src.setCanFloat(true);
        src.setCanWalkOverFences(false);

        NodeEvaluator fresh = EvaluatorCloner.cloneWithConfig(src);

        assertNotSame(src, fresh, "must not reuse evaluator scratch state");
        assertEquals(WalkNodeEvaluator.class, fresh.getClass(), "same evaluator class");
        assertTrue(fresh.canPassDoors());
        assertTrue(fresh.canOpenDoors());
        assertTrue(fresh.canFloat());
        assertFalse(fresh.canWalkOverFences());
    }

    @Test void copiesTheOppositeFlagSetToo() throws Exception {
        WalkNodeEvaluator src = new WalkNodeEvaluator();
        src.setCanPassDoors(false);
        src.setCanOpenDoors(false);
        src.setCanFloat(false);
        src.setCanWalkOverFences(true);

        NodeEvaluator fresh = EvaluatorCloner.cloneWithConfig(src);
        assertFalse(fresh.canPassDoors());
        assertFalse(fresh.canOpenDoors());
        assertFalse(fresh.canFloat());
        assertTrue(fresh.canWalkOverFences());
    }

    @Test void rejectsFlyBecauseItIsNeverEligibleForAsync() {
        FlyNodeEvaluator src = new FlyNodeEvaluator();
        assertThrows(IllegalArgumentException.class, () -> EvaluatorCloner.cloneWithConfig(src));
    }

    @Test void aquaticSwimEvaluatorClonesWithBooleanArg() throws Exception {
        SwimNodeEvaluator src = new SwimNodeEvaluator(true); // allowBreaching = true
        src.setCanFloat(true);
        NodeEvaluator fresh = EvaluatorCloner.cloneWithConfig(src);
        assertEquals(SwimNodeEvaluator.class, fresh.getClass());
        assertNotSame(src, fresh);
        assertTrue(fresh.canFloat());
        var f = SwimNodeEvaluator.class.getDeclaredField("allowBreaching");
        f.setAccessible(true);
        assertTrue(f.getBoolean(fresh), "allowBreaching must be copied to the fresh evaluator");
    }

    @Test void rejectsAmphibiousBecauseItIsNeverEligibleForAsync() {
        var src = new net.minecraft.world.level.pathfinder.AmphibiousNodeEvaluator(true);
        assertThrows(IllegalArgumentException.class, () -> EvaluatorCloner.cloneWithConfig(src));
    }
}
