package dev.pathweaver.async;

import net.minecraft.world.level.pathfinder.FlyNodeEvaluator;
import net.minecraft.world.level.pathfinder.NodeEvaluator;
import net.minecraft.world.level.pathfinder.SwimNodeEvaluator;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Proves the equivalence-critical clone: a fresh evaluator is a DIFFERENT instance of the SAME class
 * with IDENTICAL config flags. If this holds, the async search steers A* exactly as the sync search
 * would (region + findPath are vanilla's own), so async path == sync path.
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

        assertNotSame(src, fresh, "must be an independent instance (no shared state)");
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

    @Test void worksForFlyEvaluatorAlsoNoArg() throws Exception {
        FlyNodeEvaluator src = new FlyNodeEvaluator();
        src.setCanFloat(true);
        NodeEvaluator fresh = EvaluatorCloner.cloneWithConfig(src);
        assertEquals(FlyNodeEvaluator.class, fresh.getClass());
        assertTrue(fresh.canFloat());
    }

    @Test void aquaticEvaluatorWithoutNoArgCtorFailsCleanly() {
        // SwimNodeEvaluator has only a (boolean) constructor -> clone throws -> mixin falls back to sync.
        assertThrows(ReflectiveOperationException.class,
            () -> EvaluatorCloner.cloneWithConfig(new SwimNodeEvaluator(false)));
    }
}
