package dev.pathweaver.cache;

import dev.pathweaver.async.RequestTarget;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.pathfinder.NodeEvaluator;
import net.minecraft.world.level.pathfinder.PathType;

/**
 * Builds a {@link PathCacheKey} from the live mob. Main thread only, at dispatch, before the
 * evaluator's prologue runs.
 *
 * <p>Separated from the key itself so the key stays a plain value that unit tests can construct
 * without a world. This class is the only part that touches game objects.
 *
 * <p>Before the prologue matters: {@code AmphibiousNodeEvaluator.prepare} overwrites two of the
 * mob's malus values for the duration of the search and restores them afterwards. Reading the malus
 * map here captures the mob's own settled values, and store and lookup both run at this same point,
 * so the two always agree.
 */
public final class PathCacheKeys {

    /** {@code values()} clones its array on every call, and this runs once per dispatched search. */
    private static final PathType[] PATH_TYPES = PathType.values();

    private PathCacheKeys() { }

    public static PathCacheKey of(Mob mob, NodeEvaluator evaluator, RequestTarget target,
                                  Object dimensionId, int maxVisitedNodes,
                                  float maxVisitedNodesMultiplier,
                                  float capturedStepHeight, int capturedMaxFallDistance) {
        BlockPos block = mob.blockPosition();
        int[] malusBits = new int[PATH_TYPES.length];
        for (int i = 0; i < PATH_TYPES.length; i++) {
            malusBits[i] = Float.floatToIntBits(mob.getPathfindingMalus(PATH_TYPES[i]));
        }
        return new PathCacheKey(
            dimensionId, block.getX(), block.getY(), block.getZ(),
            target, evaluator.getClass(), mob.getClass(),
            evaluatorFlags(evaluator), maxVisitedNodes,
            Float.floatToIntBits(maxVisitedNodesMultiplier),
            Float.floatToIntBits(capturedStepHeight), capturedMaxFallDistance,
            Float.floatToIntBits(mob.getBbWidth()), Float.floatToIntBits(mob.getBbHeight()),
            mobStateFlags(mob), malusBits);
    }

    /** The four evaluator settings {@code EvaluatorCloner} copies onto the search's own evaluator. */
    static int evaluatorFlags(NodeEvaluator evaluator) {
        return (evaluator.canPassDoors() ? 1 : 0)
            | (evaluator.canOpenDoors() ? 2 : 0)
            | (evaluator.canFloat() ? 4 : 0)
            | (evaluator.canWalkOverFences() ? 8 : 0);
    }

    /** The live mob state the vanilla evaluators read during a search. */
    static int mobStateFlags(Mob mob) {
        return (mob.onGround() ? 1 : 0) | (mob.isInWater() ? 2 : 0);
    }
}
