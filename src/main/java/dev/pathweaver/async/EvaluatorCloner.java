package dev.pathweaver.async;

import net.minecraft.world.level.pathfinder.NodeEvaluator;
import net.minecraft.world.level.pathfinder.SwimNodeEvaluator;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;

/**
 * Builds a fresh {@link NodeEvaluator} of the same class as a mob's evaluator and copies the supported
 * configuration flags. This isolates evaluator scratch state from the navigation's evaluator. It does
 * not prove path equivalence because the search still consumes live-backed region and mob inputs.
 *
 * Handles the two eligible evaluator constructor shapes: no-arg ({@code WalkNodeEvaluator}) and
 * {@code SwimNodeEvaluator(allowBreaching)}. For Swim, the constructor arg is stored in the concrete
 * class's final boolean field, so the clone preserves that option.
 */
public final class EvaluatorCloner {
    private EvaluatorCloner() {}

    /**
     * @return a new eligible evaluator of {@code src.getClass()} with matching configuration.
     */
    public static NodeEvaluator cloneWithConfig(NodeEvaluator src) throws ReflectiveOperationException {
        NodeEvaluator fresh;
        if (src.getClass() == WalkNodeEvaluator.class) {
            fresh = new WalkNodeEvaluator();
        } else if (src.getClass() == SwimNodeEvaluator.class) {
            fresh = new SwimNodeEvaluator(readSwimAllowBreaching((SwimNodeEvaluator) src));
        } else {
            throw new IllegalArgumentException("Unsupported async evaluator: " + src.getClass().getName());
        }
        fresh.setCanPassDoors(src.canPassDoors());
        fresh.setCanOpenDoors(src.canOpenDoors());
        fresh.setCanFloat(src.canFloat());
        fresh.setCanWalkOverFences(src.canWalkOverFences());
        return fresh;
    }

    /** Resolved once: this runs on every Swim dispatch, and reflection lookups are not cheap. */
    private static final java.lang.reflect.Field SWIM_ALLOW_BREACHING = resolveAllowBreaching();

    private static java.lang.reflect.Field resolveAllowBreaching() {
        try {
            var field = SwimNodeEvaluator.class.getDeclaredField("allowBreaching");
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            return null;
        }
    }

    private static boolean readSwimAllowBreaching(SwimNodeEvaluator src)
            throws ReflectiveOperationException {
        if (SWIM_ALLOW_BREACHING == null) {
            throw new NoSuchFieldException("SwimNodeEvaluator.allowBreaching is unavailable");
        }
        return SWIM_ALLOW_BREACHING.getBoolean(src);
    }
}
