package dev.pathweaver.async;

import net.minecraft.world.level.pathfinder.NodeEvaluator;

/**
 * Builds a fresh {@link NodeEvaluator} of the same class as a mob's evaluator and copies the supported
 * configuration flags. This isolates evaluator scratch state from the navigation's evaluator. It does
 * not prove path equivalence because the search still consumes live-backed region and mob inputs.
 *
 * Handles both evaluator constructor shapes: no-arg ({@code WalkNodeEvaluator}, {@code FlyNodeEvaluator})
 * and the aquatic single-boolean form ({@code SwimNodeEvaluator(allowBreaching)},
 * {@code AmphibiousNodeEvaluator(prefersShallowSwimming)}). For the boolean form, the arg is stored in
 * a {@code final boolean} field on the concrete class, so we read that field off the source instance
 * and pass it to the matching constructor so that constructor option is preserved.
 */
public final class EvaluatorCloner {
    private EvaluatorCloner() {}

    /**
     * @return a new evaluator of {@code src.getClass()} with matching config, or throws if the class
     *         has neither a no-arg nor a single-boolean constructor (caller then falls back to sync).
     */
    public static NodeEvaluator cloneWithConfig(NodeEvaluator src) throws ReflectiveOperationException {
        Class<? extends NodeEvaluator> cls = src.getClass();
        NodeEvaluator fresh;
        try {
            fresh = cls.getDeclaredConstructor().newInstance();
        } catch (NoSuchMethodException noNoArg) {
            // Aquatic evaluators take a boolean constructor arg (allowBreaching / prefersShallowSwimming).
            var ctor = cls.getDeclaredConstructor(boolean.class);
            ctor.setAccessible(true);
            fresh = ctor.newInstance(readCtorBoolean(src));
        }
        fresh.setCanPassDoors(src.canPassDoors());
        fresh.setCanOpenDoors(src.canOpenDoors());
        fresh.setCanFloat(src.canFloat());
        fresh.setCanWalkOverFences(src.canWalkOverFences());
        return fresh;
    }

    /** The single-boolean constructor arg is the one {@code final boolean} field on the concrete class. */
    private static boolean readCtorBoolean(NodeEvaluator src) throws ReflectiveOperationException {
        for (java.lang.reflect.Field f : src.getClass().getDeclaredFields()) {
            if (f.getType() == boolean.class && java.lang.reflect.Modifier.isFinal(f.getModifiers())) {
                f.setAccessible(true);
                return f.getBoolean(src);
            }
        }
        throw new NoSuchFieldException("no final boolean constructor field on " + src.getClass().getName());
    }
}
