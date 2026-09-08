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
        withMaxAge(maxAge);
        EntityInstallSink sink = new EntityInstallSink();
        ResultInstaller installer = new ResultInstaller();
        EntityInstallSinkTest.FakeNav nav = new EntityInstallSinkTest.FakeNav();
        RequestKey key = new RequestKey(1L, 1L, 7);

        sink.setTick(0L);
        sink.register(key, nav, RequestTarget.of(java.util.Set.of(ASKED), 8, false, 1, 16.0f),
            false, RequestOrigin.BRAIN_SINK);
        sink.noteBrainSinkDispatch(7, ASKED);

        installer.enqueue(key, 0L, PathOutcome.success(route()), 0.0, 0.0, 0.0);
        sink.setTick(arriveTick);
        installer.drain(sink);

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

    /** The age budget is fixed at dispatch and parking must never extend it. */
    @Test
    void parkingDoesNotExtendTheBudgetTheRequestWasAdmittedUnder() {
        assertNull(deliverAt(41L, 40),
            "parking extended the dispatch-time budget instead of honouring it");
    }
}
