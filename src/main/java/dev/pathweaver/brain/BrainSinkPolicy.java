package dev.pathweaver.brain;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.pathfinder.Path;

/**
 * Decides, for one brain behaviour on one tick, whether to defer, to answer, or to stand aside.
 *
 * <p>This is the whole brain-sink protocol, and it lives here rather than in the mixin so it can be
 * driven directly. It was previously inline in {@code MoveToTargetSinkMixin}, where the only way to
 * exercise the liveness bound, the budget reset and the four deferral paths was to spawn a villager
 * on a running server and hope it took the branch you cared about. That is not enough evidence for a
 * feature that is on by default and sits on the only movement route about twenty brain-mob types
 * have.
 *
 * <p>One instance per behaviour instance, and behaviours are per mob, so the two counters below are
 * per mob without needing to be keyed by one.
 *
 * <p>Everything Minecraft-bound is behind {@link SearchPort}: taking a parked answer, asking whether
 * one is in flight, and running the probe that either dispatches or produces vanilla's own answer.
 * The policy itself touches no world state, so a test is a few lines and a fake port.
 */
public final class BrainSinkPolicy {

    /**
     * How many ticks in a row this behaviour may answer "not yet" before it must answer for real.
     *
     * <p>A LIVENESS BOUND, and without it the feature can stall a mob indefinitely. The parked answer
     * is keyed on the exact destination asked for, and several vanilla behaviours rewrite that
     * destination every tick. {@code AnimalPanic.tick} is the worst: it overwrites the walk target
     * with a FRESH random position on every tick the navigation is idle, ungated. A deferral leaves
     * the mob with no path, so the navigation stays idle, so the destination re-rolls, so the parked
     * answer is never for the question being asked -- and the animal stands still for the whole
     * five-second panic while dispatching one full search per tick. Panic is how a burning animal
     * reaches water.
     *
     * <p>Two is enough to be useful and small enough to be safe: the common case lands in one tick,
     * and anything that has not answered in two gets vanilla's synchronous answer instead.
     */
    public static final int MAX_CONSECUTIVE_DEFERRALS = 2;

    /** What the caller should do with this tick. */
    public enum Action {
        /** Let vanilla run untouched. Nothing supplied, nothing deferred. */
        RUN_VANILLA,
        /** Report "no path this tick". The caller must not let vanilla's own no-path handling run. */
        DEFER,
        /** Hand {@link Decision#path()} to vanilla's call site, and replay createPath's tail. */
        SUPPLY_FROM_PARK,
        /** Hand {@link Decision#path()} to vanilla's call site as-is. May legitimately be null. */
        SUPPLY_FROM_REFUSAL
    }

    /**
     * @param path the answer to hand back, meaningful only for the two SUPPLY actions. Null is a
     *     legitimate VALUE for {@link Action#SUPPLY_FROM_REFUSAL} -- "vanilla proved there is no
     *     route" -- which is why the action carries the meaning rather than the nullness.
     */
    public record Decision(Action action, Path path) {
        static Decision of(Action action) { return new Decision(action, null); }
    }

    /** Everything the decision needs from Minecraft, so the decision itself needs none of it. */
    public interface SearchPort {
        /** The landed answer for exactly this destination, or null. Removes it on a hit. */
        Path takeParked(int entityId, BlockPos asked);

        /** True when a search for exactly this destination is still outstanding. */
        boolean hasPending(int entityId, BlockPos asked);

        /** True when ANY request for this mob is registered, whatever its origin. */
        boolean isRegistered(int entityId);

        /**
         * Open the brain-sink window, run vanilla's {@code createPath}, close the window.
         *
         * <p>Returns a {@link Probe} rather than a bare {@code Path} because null is a legitimate
         * VALUE here -- vanilla proving there is no route -- and it would otherwise also have to mean
         * "the window could not be opened". Those need opposite answers: the first is vanilla's own
         * result and should be handed back, the second must leave the whole call to vanilla so it
         * searches for itself. Overloading null is how this codebase previously shipped a second
         * synchronous search on every refusal route.
         */
        Probe probe(double speed, BlockPos asked);
    }

    /**
     * @param ran false when the brain-sink window could not be opened because one is already open on
     *     this navigation -- something re-entered, and nothing was searched
     * @param path vanilla's answer when {@code ran}, which may legitimately be null
     */
    public record Probe(boolean ran, Path path) {
        public static Probe refused() { return new Probe(false, null); }
        public static Probe ran(Path path) { return new Probe(true, path); }
    }

    private int consecutiveDeferrals;
    private long lastDeferralTick = Long.MIN_VALUE;

    /** Test seam: how much of the deferral budget is spent. */
    public int consecutiveDeferrals() { return consecutiveDeferrals; }

    public Decision decide(int entityId, BlockPos asked, double speed, long gameTime,
                           SearchPort port) {
        // A break in the run of deferred ticks starts a fresh budget. Keyed on a tick gap, not on
        // the destination: AnimalPanic re-rolls the destination every tick, so a per-destination
        // budget reset every tick and the bound never tripped at all.
        if (gameTime != lastDeferralTick + 1L) consecutiveDeferrals = 0;

        Path landed = port.takeParked(entityId, asked);
        if (landed != null) {
            consecutiveDeferrals = 0;
            return new Decision(Action.SUPPLY_FROM_PARK, landed);
        }

        // Liveness, checked AFTER the collection attempt so a landed answer is never refused, and
        // BEFORE the probe so a mob about to be answered synchronously does not also start a search
        // nobody will collect.
        if (consecutiveDeferrals >= MAX_CONSECUTIVE_DEFERRALS) {
            consecutiveDeferrals = 0;
            return Decision.of(Action.RUN_VANILLA);
        }

        if (port.hasPending(entityId, asked)) return defer(gameTime);

        // Ask, then read what happened. Whether dispatch occurs is decided behind the safety gate,
        // the origin gate, admission and the breaker, so predicting it is not an option -- and the
        // diagnostic that reports eligibility warns in its own comment that it can disagree with
        // dispatch. Dispatch records its own slot where it registers, which is the only place that
        // knows a request was really admitted.
        Probe probe = port.probe(speed, asked);
        // The window was already open, so nothing was searched and nothing was dispatched. Leave the
        // whole call to vanilla rather than hand back an answer we do not have.
        if (!probe.ran()) return Decision.of(Action.RUN_VANILLA);

        if (port.hasPending(entityId, asked)) return defer(gameTime);

        // Registered but no slot of ours: another request for this mob is in flight and dispatch
        // either preserved or superseded it. `immediate` on those routes is the mob's CURRENTLY
        // INSTALLED path, not a search result, so handing it back would answer this destination with
        // the route to a different one.
        if (port.isRegistered(entityId)) return defer(gameTime);

        // Dispatch was refused outright, so this is vanilla's own synchronous answer: a path, or
        // null meaning no route exists. Either way it is the value vanilla would have had.
        consecutiveDeferrals = 0;
        return new Decision(Action.SUPPLY_FROM_REFUSAL, probe.path());
    }

    private Decision defer(long gameTime) {
        consecutiveDeferrals++;
        lastDeferralTick = gameTime;
        return Decision.of(Action.DEFER);
    }
}
