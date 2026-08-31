package dev.pathweaver.brain;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.pathfinder.Path;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * The brain-sink protocol, driven directly.
 *
 * <p>This exists because the feature ships ON by default on the only movement route about twenty
 * brain-mob types have, and until the policy was extracted the only way to exercise the liveness
 * bound, the budget reset or any of the four deferral paths was to spawn a villager on a running
 * server and hope it took the branch you cared about. One villager on flat stone is not evidence for
 * a default.
 */
class BrainSinkPolicyTest {

    private static final BlockPos A = new BlockPos(10, 64, 10);
    private static final BlockPos B = new BlockPos(90, 64, 90);
    private static final int MOB = 7;

    /** Records what was asked and answers what the test told it to. */
    private static final class FakePort implements BrainSinkPolicy.SearchPort {
        Path parked;
        boolean pendingBefore;
        boolean pendingAfterProbe;
        boolean registered;
        BrainSinkPolicy.Probe probeResult = BrainSinkPolicy.Probe.ran(null);
        final List<String> calls = new ArrayList<>();
        private boolean probed;

        @Override public Path takeParked(int entityId, BlockPos asked) {
            calls.add("take");
            Path p = parked;
            parked = null;
            return p;
        }
        @Override public boolean hasPending(int entityId, BlockPos asked) {
            calls.add("hasPending");
            return probed ? pendingAfterProbe : pendingBefore;
        }
        @Override public boolean isRegistered(int entityId) {
            calls.add("isRegistered");
            return registered;
        }
        @Override public BrainSinkPolicy.Probe probe(double speed, BlockPos asked) {
            calls.add("probe");
            probed = true;
            return probeResult;
        }
    }

    private static Path stubPath() {
        try {
            var f = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
            f.setAccessible(true);
            Object unsafe = f.get(null);
            var alloc = unsafe.getClass().getMethod("allocateInstance", Class.class);
            return (Path) alloc.invoke(unsafe, Path.class);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test void aLandedPathIsCollectedAndAnsweredFromThePark() {
        BrainSinkPolicy policy = new BrainSinkPolicy();
        FakePort port = new FakePort();
        Path landed = stubPath();
        port.parked = landed;

        BrainSinkPolicy.Decision d = policy.decide(MOB, A, 1.0, 100L, port);

        assertEquals(BrainSinkPolicy.Action.SUPPLY_FROM_PARK, d.action());
        assertSame(landed, d.path());
        assertEquals(List.of("take"), port.calls,
            "a landed answer must be collected before anything else is consulted; probing first "
                + "would dispatch a search for a question already answered");
    }

    @Test void aSearchStillInFlightDefers() {
        BrainSinkPolicy policy = new BrainSinkPolicy();
        FakePort port = new FakePort();
        port.pendingBefore = true;

        BrainSinkPolicy.Decision d = policy.decide(MOB, A, 1.0, 100L, port);

        assertEquals(BrainSinkPolicy.Action.DEFER, d.action());
        assertEquals(List.of("take", "hasPending"), port.calls,
            "a pending search must not be probed again");
    }

    @Test void aDispatchThatTookDefers() {
        BrainSinkPolicy policy = new BrainSinkPolicy();
        FakePort port = new FakePort();
        port.pendingAfterProbe = true;

        assertEquals(BrainSinkPolicy.Action.DEFER,
            policy.decide(MOB, A, 1.0, 100L, port).action());
    }

    /**
     * The supersede route. `probe` hands back the mob's CURRENTLY INSTALLED path there, not a search
     * result, so answering this destination with it would send the mob to a different one.
     */
    @Test void aRegistrationWithNoSlotOfOursDefersRatherThanTrustingTheProbe() {
        BrainSinkPolicy policy = new BrainSinkPolicy();
        FakePort port = new FakePort();
        port.registered = true;
        port.probeResult = BrainSinkPolicy.Probe.ran(stubPath());

        assertEquals(BrainSinkPolicy.Action.DEFER,
            policy.decide(MOB, A, 1.0, 100L, port).action());
    }

    @Test void aRefusedDispatchHandsBackVanillasOwnAnswer() {
        BrainSinkPolicy policy = new BrainSinkPolicy();
        FakePort port = new FakePort();
        Path vanilla = stubPath();
        port.probeResult = BrainSinkPolicy.Probe.ran(vanilla);

        BrainSinkPolicy.Decision d = policy.decide(MOB, A, 1.0, 100L, port);

        assertEquals(BrainSinkPolicy.Action.SUPPLY_FROM_REFUSAL, d.action());
        assertSame(vanilla, d.path());
    }

    /**
     * Null is a VALUE on the refusal route -- vanilla proved there is no route -- and must still be
     * supplied, so vanilla's own unreachable handling runs on it. Treating it as "nothing supplied"
     * is what previously ran a second full synchronous search on every refusal.
     */
    @Test void noRouteIsAnAnswerAndIsSupplied() {
        BrainSinkPolicy policy = new BrainSinkPolicy();
        FakePort port = new FakePort();
        port.probeResult = BrainSinkPolicy.Probe.ran(null);

        BrainSinkPolicy.Decision d = policy.decide(MOB, A, 1.0, 100L, port);

        assertEquals(BrainSinkPolicy.Action.SUPPLY_FROM_REFUSAL, d.action());
        assertNull(d.path());
    }

    /** A refused window means nothing was searched, so vanilla must search for itself. */
    @Test void aRefusedWindowLeavesTheWholeCallToVanilla() {
        BrainSinkPolicy policy = new BrainSinkPolicy();
        FakePort port = new FakePort();
        port.probeResult = BrainSinkPolicy.Probe.refused();

        BrainSinkPolicy.Decision d = policy.decide(MOB, A, 1.0, 100L, port);

        assertEquals(BrainSinkPolicy.Action.RUN_VANILLA, d.action(),
            "nothing was searched, so there is no answer to hand back; supplying null here would "
                + "make vanilla report the destination unreachable");
        assertNull(d.path());
    }

    /**
     * THE LIVENESS BOUND. Two deferrals, then vanilla answers, whatever the sink says.
     *
     * <p>Without it a mob whose destination is rewritten every tick -- which is exactly what
     * AnimalPanic does while the navigation is idle -- defers forever, never collects, and dispatches
     * one full search per tick while standing still.
     */
    @Test void theThirdConsecutiveDeferralIsAnsweredByVanillaInstead() {
        BrainSinkPolicy policy = new BrainSinkPolicy();
        FakePort port = new FakePort();
        port.pendingBefore = true;

        assertEquals(BrainSinkPolicy.Action.DEFER, policy.decide(MOB, A, 1.0, 10L, port).action());
        assertEquals(BrainSinkPolicy.Action.DEFER, policy.decide(MOB, A, 1.0, 11L, port).action());
        assertEquals(BrainSinkPolicy.Action.RUN_VANILLA,
            policy.decide(MOB, A, 1.0, 12L, port).action(),
            "the bound must fire on the third consecutive tick, or a mob whose target keeps moving "
                + "never gets a path at all");
        assertEquals(0, policy.consecutiveDeferrals(), "and the budget must reset when it fires");
    }

    /**
     * The budget is keyed on CONSECUTIVE TICKS, not on the destination.
     *
     * <p>Keying it on the destination was tried and is wrong in the one case the bound exists for:
     * AnimalPanic re-rolls the destination every tick, so a per-destination budget resets every tick
     * and the bound never trips. This pins the distinction.
     */
    @Test void aChangingDestinationDoesNotRefreshTheBudget() {
        BrainSinkPolicy policy = new BrainSinkPolicy();
        FakePort port = new FakePort();
        port.pendingBefore = true;

        assertEquals(BrainSinkPolicy.Action.DEFER, policy.decide(MOB, A, 1.0, 10L, port).action());
        assertEquals(BrainSinkPolicy.Action.DEFER, policy.decide(MOB, B, 1.0, 11L, port).action());
        assertEquals(BrainSinkPolicy.Action.RUN_VANILLA,
            policy.decide(MOB, A, 1.0, 12L, port).action(),
            "a destination that changes every tick must not refresh the deferral budget");
    }

    /**
     * THE BOUND MUST FIRE EVEN WHEN THE CALLS ARE NOT ON CONSECUTIVE TICKS.
     *
     * <p>This replaces a test that asserted the opposite -- that a gap in ticks starts a fresh budget
     * -- and that assertion was the defect, not the contract. Keyed on consecutive ticks, the budget
     * reset on every call whenever the brain did not invoke the start check on back-to-back ticks,
     * so the bound never fired and the deferral was unbounded. A game test caught the result: a
     * villager seven blocks from its destination, for seven hundred ticks, holding a walk target with
     * no path and no unreachable memory. The mob the bound exists to protect, frozen by the bound.
     *
     * <p>The budget is per decision now, so a gap changes nothing.
     */
    @Test void theBoundFiresEvenWhenTheCallsAreSpreadAcrossTicks() {
        BrainSinkPolicy policy = new BrainSinkPolicy();
        FakePort port = new FakePort();
        port.pendingBefore = true;

        assertEquals(BrainSinkPolicy.Action.DEFER, policy.decide(MOB, A, 1.0, 10L, port).action());
        assertEquals(BrainSinkPolicy.Action.DEFER, policy.decide(MOB, A, 1.0, 55L, port).action());
        assertEquals(BrainSinkPolicy.Action.RUN_VANILLA,
            policy.decide(MOB, A, 1.0, 900L, port).action(),
            "three deferrals with wide gaps between them must still hit the bound; keying it on "
                + "consecutive ticks meant a brain that skipped a tick got an unbounded deferral");
    }

    @Test void collectingAnAnswerRefreshesTheBudget() {
        BrainSinkPolicy policy = new BrainSinkPolicy();
        FakePort port = new FakePort();
        port.pendingBefore = true;
        policy.decide(MOB, A, 1.0, 10L, port);
        policy.decide(MOB, A, 1.0, 11L, port);

        port.pendingBefore = false;
        port.parked = stubPath();
        assertNotNull(policy.decide(MOB, A, 1.0, 12L, port).path());
        assertEquals(0, policy.consecutiveDeferrals(),
            "a collected answer means the round trip worked; the next one deserves a full budget");
    }
}
