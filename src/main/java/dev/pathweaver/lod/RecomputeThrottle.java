package dev.pathweaver.lod;

import dev.pathweaver.config.PathWeaverConfig;

import java.util.function.DoubleSupplier;

/**
 * Should this navigation recompute its route now, or has it done so recently enough for how far away
 * it is from anyone who could see the difference?
 *
 * <p>A mob that is following a route re-runs the whole search periodically to correct for the world
 * moving. Near a player that correction is the difference between a mob tracking you and a mob
 * walking into a wall. Sixty-four blocks away, with nobody close enough to be affected by it, it is
 * the same search producing very nearly the same answer, several times a second.
 *
 * <p><b>This changes behaviour, which is why it is off by default.</b> Everything else in this mod
 * gives the mob the path it would have got anyway; this gives it that path up to
 * {@code lodIntervalTicks} later. The delay is bounded and it only applies beyond
 * {@code lodMinDistanceBlocks}, but "only observable if you are far away and looking" is not the same
 * as "not observable", and the difference matters for a mod whose whole claim is that it does not
 * alter what mobs do. A server owner who wants the saving can say so.
 *
 * <p>It is a pure function of its arguments, and the mixin that calls it holds no rule of its own.
 * A rule living inside a mixin is a rule no test can execute, and this project has already shipped
 * one of those once.
 *
 * <p>What it deliberately does NOT throttle: the first path to a new destination. A mob that has just
 * acquired a target gets its route immediately at any distance. Only the periodic re-derivation of a
 * route the mob already has is delayed, because that is the part that is usually redundant.
 */
public final class RecomputeThrottle {

    private RecomputeThrottle() { }

    /**
     * @param config                   the published settings; null is treated as "do not throttle"
     * @param nearestPlayerDistanceSq  squared distance to the closest player, or
     *                                 {@link Double#MAX_VALUE} when the level holds no players.
     *                                 Squared, because the caller has it squared and taking a root
     *                                 per mob per tick to compare against a constant is waste.
     * @param tick                     the current server tick
     * @param lastRecomputeTick        the tick this navigation last recomputed, or
     *                                 {@link Long#MIN_VALUE} if it never has
     * @return true when the recompute should proceed
     */
    public static boolean allows(PathWeaverConfig config, DoubleSupplier nearestPlayerDistanceSq,
                                 long tick, long lastRecomputeTick) {
        // The supplier exists so the HOOK does not have to decide anything, including whether the
        // distance is worth working out. Finding the nearest player is a scan, and with the feature
        // off it would be a scan bought for nothing on every recompute in the game. Putting that
        // branch in the mixin would mean the mixin held a rule, which is the thing this project keeps
        // moving out of mixins. Putting it here keeps one decision in one testable place, and a test
        // asserts the supplier is never called when the feature is off.
        if (config == null) return true;
        if (!config.lodEnabled) return true;
        return allows(config, nearestPlayerDistanceSq.getAsDouble(), tick, lastRecomputeTick);
    }

    /** The decision proper, on a distance already in hand. */
    public static boolean allows(PathWeaverConfig config, double nearestPlayerDistanceSq,
                                 long tick, long lastRecomputeTick) {
        // FAILS OPEN, deliberately, and this is the one guard in the mod that does.
        //
        // Everywhere else a missing input means "do not act", because the action is ours and skipping
        // it costs a saving. Here the action is VANILLA'S, and skipping it silently changes what a
        // mob does. With no config to read, or a distance we could not work out, the right answer is
        // to let the game behave normally rather than to throttle on a guess.
        if (config == null) return true;
        if (!config.lodEnabled) return true;

        // <=, not <. The setting reads "throttle BEYOND this distance", so a mob at exactly the
        // threshold is not yet beyond it and still recomputes. Written with < first, which threw the
        // boundary away by one block; on squared distances that is invisible by inspection and the
        // test for it is the only reason it was caught.
        double threshold = (double) config.lodMinDistanceBlocks * config.lodMinDistanceBlocks;
        if (nearestPlayerDistanceSq <= threshold) return true;

        // Never recomputed: let it through. A mob whose first recompute is delayed would be following
        // a route derived before it started moving.
        if (lastRecomputeTick == Long.MIN_VALUE) return true;

        // Time moving backwards is a real case here: a world reload or a rollback resets the tick
        // counter, and (tick - last) then goes negative, which would read as "recomputed in the
        // future" and block every recompute until the clock caught up. Compared the same way the
        // result cache compares its own ages, with >= so that an interval of N means every N ticks
        // rather than every N+1.
        if (tick < lastRecomputeTick) return true;
        return tick - lastRecomputeTick >= config.lodIntervalTicks;
    }
}
