package dev.pathweaver.cache;

import dev.pathweaver.async.RequestTarget;

import java.util.Arrays;
import java.util.Objects;

/**
 * Everything a path search depends on, other than the blocks it reads.
 *
 * <p>A cache is only as sound as its key: any input left out is a bug that shows up as a mob taking
 * a route that was correct for a different mob, which nobody can reproduce and nobody will report as
 * a pathfinding problem. So the fields here are not a judgement about what probably matters. They
 * are the enumerated reads that the vanilla evaluators make from the mob during a search, taken from
 * the 26.1.2 bytecode:
 *
 * <pre>
 *   getPathfindingMalus   -> malusBits, every PathType, exact float bits
 *   getX / getY / getZ    -> blockX/Y/Z for the key, exact bits recorded on the entry
 *   blockPosition         -> blockX/Y/Z
 *   getBbWidth / getBbHeight / getBoundingBox -> widthBits, heightBits (the box is position + size)
 *   level                 -> dimensionId
 *   maxUpStep             -> stepHeightBits
 *   getMaxFallDistance    -> maxFallDistance
 *   onGround / isInWater  -> mobStateFlags
 *   canStandOnFluid       -> mobClass, which is what overrides it
 * </pre>
 *
 * <p>The request scalars live in {@link RequestTarget}, which already carries the target set, region
 * offset, upward flag, reach range and follow range for supersession. Reusing it keeps one
 * definition of "the same request" rather than two that can drift apart.
 *
 * <p>Position is held at BLOCK granularity here and the exact coordinates are recorded on the cached
 * entry instead. That is not sloppiness: it lets one lookup answer two questions at once — whether a
 * key-exact reuse was available, and whether a block-granular one would have been. Only the first is
 * ever served. See {@link PathCache}.
 *
 * <p>Not covered, and deliberately: {@code FlyNodeEvaluator.getStart} consults the mob's random
 * source, so two identical fly searches need not agree in vanilla either. A cache cannot be less
 * faithful than the thing it caches.
 */
public record PathCacheKey(Object dimensionId, int blockX, int blockY, int blockZ,
                           RequestTarget target, Class<?> evaluatorClass, Class<?> mobClass,
                           int evaluatorFlags, int maxVisitedNodes, int multiplierBits,
                           int stepHeightBits, int maxFallDistance,
                           int widthBits, int heightBits, int mobStateFlags,
                           int[] malusBits) {

    /**
     * Slot selector for {@link SectionChangeClock}, derived from the dimension rather than stored
     * beside it.
     *
     * <p>The dimension is compared by value here and hashed there for two different reasons. A
     * collision in the clock costs a discarded cache entry; a collision in this key would serve an
     * overworld route to a mob in the nether. Only one of those may be answered by a hash.
     */
    public int dimensionHash() {
        return dimensionId.hashCode();
    }

    public PathCacheKey {
        Objects.requireNonNull(dimensionId, "dimensionId");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(evaluatorClass, "evaluatorClass");
        Objects.requireNonNull(mobClass, "mobClass");
        Objects.requireNonNull(malusBits, "malusBits");
    }

    // A record's generated equals/hashCode compare an array by identity, so two keys built from
    // identical mobs would never match and the cache would have a permanent 0% hit rate that looked
    // exactly like "sharing does not happen here". Compared by contents instead.
    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof PathCacheKey that)) return false;
        return dimensionId.equals(that.dimensionId)
            && blockX == that.blockX && blockY == that.blockY && blockZ == that.blockZ
            && evaluatorClass == that.evaluatorClass && mobClass == that.mobClass
            && evaluatorFlags == that.evaluatorFlags
            && maxVisitedNodes == that.maxVisitedNodes && multiplierBits == that.multiplierBits
            && stepHeightBits == that.stepHeightBits && maxFallDistance == that.maxFallDistance
            && widthBits == that.widthBits && heightBits == that.heightBits
            && mobStateFlags == that.mobStateFlags
            && Arrays.equals(malusBits, that.malusBits)
            && target.equals(that.target);
    }

    @Override
    public int hashCode() {
        int h = dimensionId.hashCode();
        h = 31 * h + blockX;
        h = 31 * h + blockY;
        h = 31 * h + blockZ;
        h = 31 * h + target.hashCode();
        h = 31 * h + evaluatorClass.hashCode();
        h = 31 * h + mobClass.hashCode();
        h = 31 * h + evaluatorFlags;
        h = 31 * h + maxVisitedNodes;
        h = 31 * h + multiplierBits;
        h = 31 * h + stepHeightBits;
        h = 31 * h + maxFallDistance;
        h = 31 * h + widthBits;
        h = 31 * h + heightBits;
        h = 31 * h + mobStateFlags;
        return 31 * h + Arrays.hashCode(malusBits);
    }
}
