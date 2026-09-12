package dev.pathweaver.lod;

import dev.pathweaver.config.PathWeaverConfig;

import java.util.function.DoubleSupplier;

/**
 * Should this navigation recompute its route now, or has it done so recently enough for how far away
 * it is from anyone who could see the difference?
 *
 * <p><b>What vanilla actually does, because the first version of this class got it wrong.</b>
 * {@code PathNavigation.recomputePath} is not a periodic refresh. It runs in two situations only: a
 * block changed on the route this mob is walking ({@code ServerLevel.sendBlockUpdated} calls it for
 * every nearby navigation whose path crosses that block), and the per-tick retry that follows when
 * such a call had to be deferred. If nothing changes near a mob's route, the method is never called
 * at all. Those are the only two callers in the game; every other class in the jar was scanned.
 *
 * <p>And when it is called, it already refuses most of the time:
 *
 * <pre>
 *   if (level.getGameTime() - timeLastRecompute &gt; 20 &amp;&amp; canUpdatePath()) { ...search... }
 *   else { hasDelayedRecomputation = true; }
 * </pre>
 *
 * <p>So vanilla's own floor is {@value #VANILLA_RECOMPUTE_PERIOD_TICKS} ticks between real searches
 * for one navigation, and the calls in between cost a clock read, a comparison and a field write.
 * This class widens that floor for distant mobs. It does not invent a limit where there was none.
 *
 * <p><b>This changes behaviour, which is why it is off by default.</b> Everything else in this mod
 * gives the mob the path it would have got anyway; this lets a distant mob keep walking an
 * out-of-date route for longer after the terrain under it changes, up to {@code lodIntervalTicks}
 * instead of vanilla's {@value #VANILLA_RECOMPUTE_PERIOD_TICKS}. Bounded and remote is not the same
 * as unobservable, and a mod whose whole claim is that it does not alter what mobs do has to be
 * asked before it does this.
 *
 * <p>It is a pure function of its arguments, and the mixin that calls it holds no rule of its own.
 * A rule living inside a mixin is a rule no test can execute, and this project has already shipped
 * one of those once.
 *
 * <p>What it deliberately does NOT throttle: the first path to a new destination. A mob that has just
 * acquired a target gets its route immediately at any distance, because a new destination goes
 * through {@code moveTo} and {@code createPath} and never reaches this method.
 */
public final class RecomputeThrottle {

    /**
     * The soonest vanilla will run two real path searches for one navigation, in ticks.
     *
     * <p>Derived, not chosen: {@code recomputePath} acts only when
     * {@code level.getGameTime() - timeLastRecompute > 20}, so the first tick on which a second
     * search can happen is the twenty-first. Read from the compiled {@code PathNavigation} of both
     * 26.1.2 and 26.2, where the constant and the comparison are identical.
     *
     * <p>It is the floor of {@code lodIntervalTicks} for a reason. An interval below this cannot
     * remove a single search, because vanilla would have refused those calls anyway, so accepting
     * one would give an operator a setting that reads as armed and does nothing.
     */
    public static final int VANILLA_RECOMPUTE_PERIOD_TICKS = 21;

    private RecomputeThrottle() { }

    /**
     * @param config                   the published settings; null is treated as "do not throttle"
     * @param nearestPlayerDistanceSq  squared distance to the closest player, or
     *                                 {@link Double#MAX_VALUE} when the level holds no players.
     *                                 Squared, because the caller has it squared and taking a root
     *                                 per mob per tick to compare against a constant is waste.
     * @param gameTime                 {@code level.getGameTime()}, the clock vanilla stamps with
     * @param lastRecomputeGameTime    vanilla's {@code timeLastRecompute} for this navigation, which
     *                                 is 0 until it has recomputed once
     * @return true when the recompute should proceed
     */
    public static boolean allows(PathWeaverConfig config, DoubleSupplier nearestPlayerDistanceSq,
                                 long gameTime, long lastRecomputeGameTime) {
        // The supplier exists so the HOOK does not have to decide anything, including whether the
        // distance is worth working out. Finding the nearest player is a scan, and with the feature
        // off it would be a scan bought for nothing on every recompute in the game. Putting that
        // branch in the mixin would mean the mixin held a rule, which is the thing this project keeps
        // moving out of mixins. Putting it here keeps one decision in one testable place, and a test
        // asserts the supplier is never called when the feature is off.
        if (config == null) return true;
        if (!config.lodEnabled) return true;
        return allows(config, nearestPlayerDistanceSq.getAsDouble(), gameTime, lastRecomputeGameTime);
    }

    /** The decision proper, on a distance already in hand. */
    public static boolean allows(PathWeaverConfig config, double nearestPlayerDistanceSq,
                                 long gameTime, long lastRecomputeGameTime) {
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

        // Vanilla has never recomputed this navigation: let it through. Its stamp is a game time, so
        // it is zero until the first real search and can never legitimately be negative. Taking this
        // branch before the subtraction below also means no caller can overflow it with a sentinel.
        if (lastRecomputeGameTime <= 0L) return true;

        // Time moving backwards is a real case here: a rollback or a restored backup can move
        // getGameTime() behind a stamp taken before it, and (now - then) would then read as
        // "recomputed in the future" and block every recompute until the clock caught up.
        if (gameTime < lastRecomputeGameTime) return true;

        // >= so that an interval of N means every N ticks rather than every N+1.
        //
        // The comparison is against VANILLA'S stamp, not a counter of our own, and that distinction
        // is the whole fix in 0.9.0. A counter incremented whenever this method said yes was counting
        // calls, and vanilla answers most calls by deferring rather than searching, so the interval
        // did not mean what the setting said. Worse, PathWeaver rolls timeLastRecompute BACK when an
        // async search fails to install (see pathweaver$rearmRecompute); against our own counter that
        // repair was itself throttled away, leaving the mob on a stale route. Against vanilla's stamp
        // the rolled-back value is old, so the repair is allowed, which is what it is for.
        return gameTime - lastRecomputeGameTime >= config.lodIntervalTicks;
    }
}
