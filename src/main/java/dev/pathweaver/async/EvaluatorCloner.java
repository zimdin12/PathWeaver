package dev.pathweaver.async;

import net.minecraft.world.level.pathfinder.NodeEvaluator;

/**
 * Builds a fresh, independent {@link NodeEvaluator} of the same class as a mob's evaluator, with its
 * configuration flags copied. This is the equivalence-critical step: the async search runs on this
 * fresh evaluator instead of the shared {@code this.nodeEvaluator}, so it holds zero cross-thread
 * state — while producing the identical path, because the config that steers A* is copied exactly.
 * (The region and the {@code findPath} call itself are vanilla's own, byte-for-byte.)
 *
 * v1 supports no-arg evaluators — {@code WalkNodeEvaluator} and {@code FlyNodeEvaluator} (land +
 * flying mobs, the overwhelming majority of pathfinding load). {@code SwimNodeEvaluator} and
 * {@code AmphibiousNodeEvaluator} take a boolean constructor arg; cloning them throws and the caller
 * falls back to synchronous pathing (safe, just no async benefit for aquatic mobs).
 */
public final class EvaluatorCloner {
    private EvaluatorCloner() {}

    /**
     * @return a new evaluator of {@code src.getClass()} with matching config, or throws if the class
     *         has no accessible no-arg constructor (caller then falls back to synchronous pathing).
     */
    public static NodeEvaluator cloneWithConfig(NodeEvaluator src) throws ReflectiveOperationException {
        NodeEvaluator fresh = src.getClass().getDeclaredConstructor().newInstance();
        fresh.setCanPassDoors(src.canPassDoors());
        fresh.setCanOpenDoors(src.canOpenDoors());
        fresh.setCanFloat(src.canFloat());
        fresh.setCanWalkOverFences(src.canWalkOverFences());
        return fresh;
    }
}
