package dev.pathweaver.async;

import net.minecraft.world.level.pathfinder.FlyNodeEvaluator;
import net.minecraft.world.level.pathfinder.SwimNodeEvaluator;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EvaluatorCallbackContractTest {
    static final class CustomWalk extends WalkNodeEvaluator { }

    @Test void exactWalkRequiresOneStartAndOneDoneReplay() {
        EvaluatorCallbackContract contract = EvaluatorCallbackContract.forAsyncEvaluator(WalkNodeEvaluator.class);
        assertEquals(1, contract.startCount());
        assertEquals(1, contract.doneCount());
    }

    @Test void exactSwimRequiresNoMobCallbackReplay() {
        EvaluatorCallbackContract contract = EvaluatorCallbackContract.forAsyncEvaluator(SwimNodeEvaluator.class);
        assertEquals(0, contract.startCount());
        assertEquals(0, contract.doneCount());
    }

    @Test void unsupportedAndSubclassEvaluatorsFailClosed() {
        assertThrows(IllegalArgumentException.class,
            () -> EvaluatorCallbackContract.forAsyncEvaluator(FlyNodeEvaluator.class));
        assertThrows(IllegalArgumentException.class,
            () -> EvaluatorCallbackContract.forAsyncEvaluator(CustomWalk.class));
    }
}
