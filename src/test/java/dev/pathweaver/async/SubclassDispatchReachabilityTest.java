package dev.pathweaver.async;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;

import net.minecraft.world.level.pathfinder.SwimNodeEvaluator;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import org.junit.jupiter.api.Test;

/**
 * Pins the interaction between the safety gate and the callback contract.
 *
 * <p>These are two independent admission decisions and they disagreed. The gate was widened to admit
 * third-party evaluator subclasses at the unsafe tier, but the callback contract still matches on
 * exact class identity, so every admitted subclass reached dispatch and then threw. The failure was
 * invisible because the throw lands inside the dispatch try-block and degrades to a synchronous
 * search: the mod stayed correct while doing the whole setup, submitting to the pool, counting a
 * dispatch, and then unwinding it every single time.
 *
 * <p>A widening in one gate that another gate silently rejects is worse than no widening at all, so
 * the relationship is pinned here rather than left to be rediscovered.
 */
class SubclassDispatchReachabilityTest {

    private static final class ThirdPartyWalk extends WalkNodeEvaluator {}

    private static final class ThirdPartySwim extends SwimNodeEvaluator {
        ThirdPartySwim() {
            super(false);
        }
    }

    @Test
    void exactVanillaEvaluatorsHaveAContract() {
        assertEquals(new EvaluatorCallbackContract(1, 1),
            EvaluatorCallbackContract.forAsyncEvaluator(WalkNodeEvaluator.class));
        assertEquals(new EvaluatorCallbackContract(0, 0),
            EvaluatorCallbackContract.forAsyncEvaluator(SwimNodeEvaluator.class));
    }

    @Test
    void subclassesAdmittedByTheGateCurrentlyHaveNoContract() {
        // Documents the defect: the gate says yes, the contract says no, and dispatch aborts.
        assertThrows(IllegalArgumentException.class,
            () -> EvaluatorCallbackContract.forAsyncEvaluator(ThirdPartyWalk.class));
        assertThrows(IllegalArgumentException.class,
            () -> EvaluatorCallbackContract.forAsyncEvaluator(ThirdPartySwim.class));
    }
}
