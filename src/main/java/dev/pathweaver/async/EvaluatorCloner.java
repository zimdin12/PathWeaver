package dev.pathweaver.async;

import net.minecraft.world.level.pathfinder.NodeEvaluator;

/**
 * Builds a fresh, independent {@link NodeEvaluator} of the same class as a mob's evaluator, with its
 * configuration flags copied. This is the equivalence-critical step: the async search runs on this
 * fresh evaluator instead of the shared {@code this.nodeEvaluator}, so it holds zero cross-thread
 * state — while producing the identical path, because the config that steers A* is copied exactly.
 * (The region and the {@code findPath} call itself are vanilla's own, byte-for-byte.)
 *
 * Handles both evaluator constructor shapes: no-arg ({@code WalkNodeEvaluator}, {@code FlyNodeEvaluator})
 * and the aquatic single-boolean form ({@code SwimNodeEvaluator(allowBreaching)},
 * {@code AmphibiousNodeEvaluator(prefersShallowSwimming)}). For the boolean form, the arg is stored in
 * a {@code final boolean} field on the concrete class, so we read that field off the source instance
 * and pass it to the matching constructor — the fresh evaluator is behaviourally identical.
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
