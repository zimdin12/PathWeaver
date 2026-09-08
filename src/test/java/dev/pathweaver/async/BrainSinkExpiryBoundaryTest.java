package dev.pathweaver.async;

import dev.pathweaver.config.PathWeaverConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One expiry rule, across every stage a brain-sink result passes through.
 *
 * <p>Four sites decide whether a result has expired and two of them disagreed. {@code isStale} calls
 * a result stale only at {@code age > maxResultAgeTicks}, and {@code answers} accepts
 * {@code tick <= expiryTick}; both treat the final allowed age as fresh. {@code parkForBrain} and the
 * sweep rejected that same age. So a result the freshness policy accepted was thrown away at parking
 * and recorded as ARRIVED_STALE, which wasted the search and put a wrong reason in the row an
 * operator reads.
 *
 * <p>The comment above {@code answers} already described this failure in detail, including that at
 * {@code maxResultAgeTicks=1} it means every brain mob dispatches a search nobody collects and then
 * runs a synchronous one anyway. It had been fixed in one of the two places. The setting is clamped
 * to a minimum of 1, so the pathological case is reachable from configuration.
 *
 * <p>These drive the real registration, enqueue, drain and collect route rather than calling
 * {@code parkForBrain}, because the defect was a disagreement BETWEEN stages and a test that pokes
 * one stage cannot see it.
 */
class BrainSinkExpiryBoundaryTest {

    private static final BlockPos ASKED = new BlockPos(10, 64, 10);

    @AfterEach
    void restoreConfig() {
        PathWeaverConfig.set(new PathWeaverConfig());
    }

    private static void withMaxAge(int ticks) {
        PathWeaverConfig config = new PathWeaverConfig();
        config.maxResultAgeTicks = ticks;
        PathWeaverConfig.set(config);
    }

    private static Path route() {
        List<Node> nodes = new ArrayList<>();
        nodes.add(new Node(10, 64, 10));
        nodes.add(new Node(11, 64, 10));
        return new Path(nodes, new BlockPos(11, 64, 10), true);
    }

    /**
     * Dispatch at tick 0, let the result land at {@code arriveTick}, and report what the brain got.
     * Every step is the production route: register, note the dispatch, enqueue a worker success,
     * drain on the main thread, then collect the way the behaviour does.
     */
    private static Path deliverAt(long arriveTick, int maxAge) {
        return deliver(arriveTick, arriveTick, maxAge, maxAge);
    }

    /**
     * Dispatch at tick 0 under {@code maxAge}, land the result at {@code arriveTick}, optionally
     * publish {@code maxAgeAtCollection} after it has parked, then collect at {@code collectTick}.
     *
     * <p>The setting change happens between parking and collection deliberately. That is the only
     * moment at which an implementation that re-reads the live budget behaves differently from one
     * that honours the budget the request was admitted under, and the two were indistinguishable
     * while arrival and collection were the same tick.
     */
    private static Path deliver(long arriveTick, long collectTick, int maxAge,
                                int maxAgeAtCollection) {
        withMaxAge(maxAge);
        EntityInstallSink sink = new EntityInstallSink();
        ResultInstaller installer = new ResultInstaller();
        RequestKey key = new RequestKey(1L, 1L, 7);

        sink.setTick(0L);
        sink.register(key, new EntityInstallSinkTest.FakeNav(),
            RequestTarget.of(java.util.Set.of(ASKED), 8, false, 1, 16.0f), false,
            RequestOrigin.BRAIN_SINK);
        sink.noteBrainSinkDispatch(7, ASKED);

        installer.enqueue(key, 0L, PathOutcome.success(route()), 0.0, 0.0, 0.0);
        sink.setTick(arriveTick);
        installer.drain(sink);

        if (maxAgeAtCollection != maxAge) withMaxAge(maxAgeAtCollection);
        sink.setTick(collectTick);
        return sink.takeBrainSinkPath(7, ASKED);
    }

    @Test
    void aResultArrivingImmediatelyIsCollected() {
        // The positive control. Without it, every "not collected" assertion below could be passing
        // because the harness never delivers anything at all.
        assertNotNull(deliverAt(0L, 40), "a same-tick result was not collected; the route is broken");
    }

    /**
     * The fix. At exactly the configured maximum age the result is still fresh by the policy that
     * admits it, so parking must accept it.
     */
    @Test
    void aResultArrivingAtTheLastAllowedAgeIsCollected() {
        assertNotNull(deliverAt(40L, 40),
            "a result at exactly maxResultAgeTicks was discarded, though isStale calls it fresh");
    }

    /** One tick past the budget is genuinely expired and must not be parked. */
    @Test
    void aResultArrivingOneTickLateIsNotCollected() {
        assertNull(deliverAt(41L, 40), "a result past the age budget was parked anyway");
    }

    /**
     * The configuration-reachable pathological case. `validatePostLoad` clamps this setting to a
     * minimum of 1, so an operator can select it. Before the fix, a result landing one tick after
     * dispatch was never collected, which is the "strictly more work than vanilla" outcome the
     * production comment warns about.
     */
    @Test
    void atTheClampedMinimumTheFinalAllowedAgeStillWorks() {
        assertNotNull(deliverAt(1L, 1),
            "at maxResultAgeTicks=1 a brain mob dispatches a search nobody can collect");
        assertNull(deliverAt(2L, 1), "past the budget at the clamped minimum must still expire");
    }

    /** The sweep must not retire a slot whose result is still admissible. */
    @Test
    void theSweepLeavesASlotAliveAtTheFinalAllowedAge() {
        withMaxAge(40);
        EntityInstallSink sink = new EntityInstallSink();
        sink.setTick(0L);
        sink.noteBrainSinkDispatch(7, ASKED);

        sink.setTick(40L);
        sink.shouldForceSync(7, 40L, RequestOrigin.MOVE_TO);   // drives the sweep
        assertTrue(sink.hasPendingBrainSink(7, ASKED),
            "the sweep retired a slot one tick before the policy that admits its result");

        sink.setTick(41L);
        sink.shouldForceSync(7, 41L, RequestOrigin.MOVE_TO);
        assertFalse(sink.hasPendingBrainSink(7, ASKED), "an expired slot was not swept");
    }

    /** A parked path is handed out once. A second behaviour tick must not receive it again. */
    @Test
    void aParkedRouteIsConsumedExactlyOnce() {
        withMaxAge(40);
        EntityInstallSink sink = new EntityInstallSink();
        ResultInstaller installer = new ResultInstaller();
        RequestKey key = new RequestKey(1L, 1L, 7);
        sink.setTick(0L);
        sink.register(key, new EntityInstallSinkTest.FakeNav(),
            RequestTarget.of(java.util.Set.of(ASKED), 8, false, 1, 16.0f), false,
            RequestOrigin.BRAIN_SINK);
        sink.noteBrainSinkDispatch(7, ASKED);
        installer.enqueue(key, 0L, PathOutcome.success(route()), 0.0, 0.0, 0.0);
        sink.setTick(5L);
        installer.drain(sink);

        assertNotNull(sink.takeBrainSinkPath(7, ASKED));
        assertNull(sink.takeBrainSinkPath(7, ASKED), "the same parked route was handed out twice");
    }

    /** A slot answers for the destination it was dispatched for, and not for another. */
    @Test
    void aSlotDoesNotAnswerForADifferentDestination() {
        withMaxAge(40);
        EntityInstallSink sink = new EntityInstallSink();
        sink.setTick(0L);
        sink.noteBrainSinkDispatch(7, ASKED);
        assertTrue(sink.hasPendingBrainSink(7, ASKED));
        assertFalse(sink.hasPendingBrainSink(7, new BlockPos(99, 64, 99)),
            "a slot answered for a destination it was never dispatched for");
    }

    /**
     * Server time can move backwards, on a reload or a rollback. A slot dispatched in what is now the
     * future must not be treated as expired, and must not be treated as infinitely valid either.
     */
    @Test
    void aTickRollbackDoesNotExpireAPendingSlot() {
        withMaxAge(40);
        EntityInstallSink sink = new EntityInstallSink();
        sink.setTick(1000L);
        sink.noteBrainSinkDispatch(7, ASKED);

        sink.setTick(900L);
        sink.shouldForceSync(7, 900L, RequestOrigin.MOVE_TO);
        assertTrue(sink.hasPendingBrainSink(7, ASKED),
            "a slot was swept because the clock moved backwards past its dispatch");
    }

    /**
     * Parking carries the dispatch deadline, rather than starting a new one from the arrival tick.
     *
     * <p>The previous version of this only checked an arrival that was ALREADY too late, which
     * parking rejects before the deadline is ever stored. That leaves the interesting mutation alive:
     * storing {@code currentTick + maxResultAgeTicks} at parking instead of the slot's own expiry.
     * Every earlier assertion here survives it, because they park at or before the deadline and
     * collect on the same tick. This one parks early and collects late, where a restarted budget
     * shows up as a result that outlives the window its request was admitted under.
     */
    @Test
    void parkingCarriesTheDispatchDeadlineRatherThanRestartingIt() {
        // Parked at 5 with a budget of 40, so the deadline is tick 40 and not 45.
        assertNotNull(deliver(5L, 40L, 40, 40),
            "a result parked early was not collectable at its own deadline");
        assertNull(deliver(5L, 41L, 40, 40),
            "parking restarted the budget from the arrival tick: the result outlived tick 40");
        assertNull(deliver(5L, 45L, 40, 40),
            "a result was collectable 45 ticks after dispatch on a 40-tick budget");
    }

    /** The age budget is fixed at dispatch and parking must never extend it. */
    @Test
    void parkingDoesNotExtendTheBudgetTheRequestWasAdmittedUnder() {
        assertNull(deliverAt(41L, 40),
            "parking extended the dispatch-time budget instead of honouring it");
    }

    /**
     * Server time can move backwards, and a parked result must not answer a question from before it
     * was dispatched.
     *
     * <p>{@code isStale} has always refused a negative age. Collection checked only the upper bound,
     * so a slot dispatched at 1000 and parked at 1001 answered a question asked at tick 900 and gave
     * a mob a route computed a hundred ticks in its own future. The two checks are the same rule at
     * different moments and now agree.
     */
    @Test
    void aParkedResultDoesNotAnswerAQuestionFromBeforeItsDispatch() {
        withMaxAge(40);
        EntityInstallSink sink = new EntityInstallSink();
        ResultInstaller installer = new ResultInstaller();
        RequestKey key = new RequestKey(1L, 1L, 7);

        sink.setTick(1000L);
        sink.register(key, new EntityInstallSinkTest.FakeNav(),
            RequestTarget.of(java.util.Set.of(ASKED), 8, false, 1, 16.0f), false,
            RequestOrigin.BRAIN_SINK);
        sink.noteBrainSinkDispatch(7, ASKED);
        installer.enqueue(key, 1000L, PathOutcome.success(route()), 0.0, 0.0, 0.0);
        sink.setTick(1001L);
        installer.drain(sink);

        // The control: at its own tick the parked result is collectable, so a null below is the
        // rollback being refused rather than nothing having been parked at all.
        EntityInstallSink other = parkedAt(1000L, 1001L);
        assertNotNull(other.takeBrainSinkPath(7, ASKED), "nothing was parked; the test proves nothing");

        sink.setTick(900L);
        assertNull(sink.takeBrainSinkPath(7, ASKED),
            "a parked result answered a question asked 100 ticks before it was dispatched");
    }

    /** A sink holding one parked result, dispatched and landed at the given ticks. */
    private static EntityInstallSink parkedAt(long dispatchTick, long arriveTick) {
        EntityInstallSink sink = new EntityInstallSink();
        ResultInstaller installer = new ResultInstaller();
        RequestKey key = new RequestKey(1L, 2L, 7);
        sink.setTick(dispatchTick);
        sink.register(key, new EntityInstallSinkTest.FakeNav(),
            RequestTarget.of(java.util.Set.of(ASKED), 8, false, 1, 16.0f), false,
            RequestOrigin.BRAIN_SINK);
        sink.noteBrainSinkDispatch(7, ASKED);
        installer.enqueue(key, dispatchTick, PathOutcome.success(route()), 0.0, 0.0, 0.0);
        sink.setTick(arriveTick);
        installer.drain(sink);
        return sink;
    }

    /**
     * The stated after-parking policy: the budget is frozen once a result is parked.
     *
     * <p>This is a decision, not a derivation, so it is pinned rather than left to be rediscovered.
     * Lowering the setting after a result has parked does not retract it, and raising the setting
     * does not extend it. The alternative, re-reading the live setting at collection, would let a
     * raise resurrect a result that had already expired unread, which is the one direction none of
     * the arrival rules take.
     */
    @Test
    void aLiveBudgetChangeAfterParkingNeitherRetractsNorExtendsTheResult() {
        assertNotNull(deliver(5L, 6L, 40, 1),
            "lowering the budget after parking retracted a result already admitted under the old one");
        assertNull(deliver(5L, 41L, 40, 400),
            "raising the budget after parking resurrected a result past its own deadline");
    }
}
