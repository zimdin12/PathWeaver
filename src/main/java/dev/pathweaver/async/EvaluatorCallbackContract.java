package dev.pathweaver.async;

import net.minecraft.world.level.pathfinder.SwimNodeEvaluator;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;

/** Exact vanilla evaluator callback multiplicity for one accepted async search. */
public record EvaluatorCallbackContract(int startCount, int doneCount) {
    private static final EvaluatorCallbackContract WALK = new EvaluatorCallbackContract(1, 1);
    private static final EvaluatorCallbackContract SWIM = new EvaluatorCallbackContract(0, 0);

    public static EvaluatorCallbackContract forAsyncEvaluator(Class<?> evaluatorClass) {
        if (evaluatorClass == WalkNodeEvaluator.class) return WALK;
        if (evaluatorClass == SwimNodeEvaluator.class) return SWIM;
        throw new IllegalArgumentException("Unsupported async evaluator: " + evaluatorClass.getName());
    }
}
