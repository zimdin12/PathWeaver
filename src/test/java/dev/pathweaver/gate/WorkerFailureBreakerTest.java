package dev.pathweaver.gate;

import dev.pathweaver.config.CompatibilityTier;
import dev.pathweaver.config.PathWeaverConfig;
import net.minecraft.world.level.pathfinder.AmphibiousNodeEvaluator;
import net.minecraft.world.level.pathfinder.FlyNodeEvaluator;
import net.minecraft.world.level.pathfinder.SwimNodeEvaluator;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The breaker's behaviour, and the reasons each rule exists.
 *
 * <p>Every assertion here was written against a mutation that was compiled and observed to fail. A
 * test that passes with the rule reverted is not evidence, and this project has now shipped six
 * defects that a reading-based review missed and an executed mutation caught.
 */
class WorkerFailureBreakerTest {

    private int savedLimit;
    private int savedWindow;

    @BeforeEach
    void arm() {
        PathWeaverConfig config = PathWeaverConfig.get();
        savedLimit = config.workerFailureLimit;
        savedWindow = config.workerFailureWindowTicks;
        config.workerFailureLimit = 3;
        config.workerFailureWindowTicks = 1200;
        WorkerFailureBreaker.reset();
    }

    @AfterEach
    void disarm() {
        PathWeaverConfig config = PathWeaverConfig.get();
        config.workerFailureLimit = savedLimit;
        config.workerFailureWindowTicks = savedWindow;
        WorkerFailureBreaker.reset();
    }

    private static void fail(Class<?> evaluator) {
        WorkerFailureBreaker.recordSearchFailure(evaluator, new IllegalStateException("synthetic"));
    }

    @Test
    void theFamilyTripsOnTheThirdFailureAndNotTheSecond() {
        fail(WalkNodeEvaluator.class);
        fail(WalkNodeEvaluator.class);
        assertFalse(SafetyGate.isDeniedByRuntimeFailure(WalkNodeEvaluator.class),
            "two failures inside the window must not switch a family off -- the whole reason the "
                + "threshold is not one is that an isolated hiccup is not an incompatibility");

        fail(WalkNodeEvaluator.class);
        assertTrue(SafetyGate.isDeniedByRuntimeFailure(WalkNodeEvaluator.class),
            "the third inside the window must");
    }

    /**
     * The window is the whole reason this mechanism is affordable.
     *
     * <p>A cumulative counter converges on a certain trip given enough uptime, and a false trip is
     * not a free no-op: being vanilla is what the user installed the mod to stop. Three failures a
     * fortnight apart on a healthy server must leave it dispatching.
     */
    @Test
    void failuresSpreadBeyondTheWindowDoNotAccumulate() {
        WorkerFailureBreaker.setTick(0L);
        fail(WalkNodeEvaluator.class);
        WorkerFailureBreaker.setTick(5_000L);
        fail(WalkNodeEvaluator.class);
        WorkerFailureBreaker.setTick(10_000L);
        fail(WalkNodeEvaluator.class);

        assertFalse(SafetyGate.isDeniedByRuntimeFailure(WalkNodeEvaluator.class),
            "three failures hours apart is an occasional contained race, which this project's own "
                + "Lithium audit calls expected -- switching the mod off for it would be the false "
                + "positive the window exists to prevent");
        assertEquals(1, WorkerFailureBreaker.windowedCount(WalkNodeEvaluator.class),
            "and the window must have restarted rather than merely not tripped");
    }

    /**
     * A failure through a subclass counts against the family whose code actually ran.
     *
     * <p>Fly, Amphibious, Frog and Creaking all extend {@code WalkNodeEvaluator} and execute its
     * code. Counting per exact class would file one failure against each of four keys and trip
     * nothing — the configured threshold silently multiplied by the number of subclasses, which is
     * the sort of arithmetic nobody notices until the mechanism does not fire.
     */
    @Test
    void failuresThroughSubclassesCountAgainstTheFamilyWhoseCodeRan() {
        fail(FlyNodeEvaluator.class);
        fail(AmphibiousNodeEvaluator.class);
        fail(WalkNodeEvaluator.class);

        assertTrue(SafetyGate.isDeniedByRuntimeFailure(WalkNodeEvaluator.class),
            "three failures across three land families are three failures of Walk's code");
        assertTrue(SafetyGate.isDeniedByRuntimeFailure(FlyNodeEvaluator.class),
            "and denying Walk denies everything derived from it, as it already does for the scan");
        assertFalse(SafetyGate.isDeniedByRuntimeFailure(SwimNodeEvaluator.class),
            "swim shares none of that code and must keep dispatching");
    }

    /**
     * The most important test in this feature.
     *
     * <p>A runtime trip must not be waivable by any tier. The unsafe tier waives what the scan
     * PREDICTED; the breaker fires precisely when that prediction was wrong, so a tier that could
     * waive it would waive the only mechanism that had actual evidence. This is why the trip lives in
     * its own set rather than in {@code deniedBySafety}, which the unsafe tier clears wholesale at
     * startup.
     */
    @Test
    void aTripSurvivesTheUnsafeTierAndActuallyReachesTheDispatchGate() {
        CompatibilityTier savedTier = PathWeaverConfig.get().compatibilityTier;
        java.util.Set<Class<?>> savedDenials;
        synchronized (SafetyGate.deniedBySafety) {
            savedDenials = java.util.Set.copyOf(SafetyGate.deniedBySafety);
        }
        try {
            // Clear the scan's denials FIRST, and this is the whole point of the test rather than
            // setup noise. deniedBySafety is initialised to every family -- fail-closed before
            // discovery runs -- so in a unit environment isAllowed() is already false for reasons
            // that have nothing to do with the breaker. The first version of this test asserted
            // isAllowed()==false against that pre-existing denial and passed happily with the
            // breaker's wiring into isDenied() deleted: a mutation that leaves the mod recording
            // trips and dispatching anyway, i.e. the entire feature inert, went green.
            SafetyGate.replaceDenials(java.util.Set.of());
            PathWeaverConfig.get().compatibilityTier = CompatibilityTier.UNSAFE;
            assertTrue(SafetyGate.isAllowed(WalkNodeEvaluator.class),
                "precondition: with the scan's denials cleared, Walk dispatches");

            fail(WalkNodeEvaluator.class);
            fail(WalkNodeEvaluator.class);
            fail(WalkNodeEvaluator.class);

            assertTrue(SafetyGate.isDeniedByRuntimeFailure(WalkNodeEvaluator.class),
                "the trip itself must land regardless of tier");
            assertFalse(SafetyGate.isAllowed(WalkNodeEvaluator.class),
                "and it must reach the gate dispatch actually asks. A trip the unsafe tier can waive "
                    + "is a mechanism that switches itself off exactly when it finally has evidence "
                    + "-- the tier waives what the scan PREDICTED, and the breaker fires because that "
                    + "prediction was wrong.");
            assertFalse(SafetyGate.canDispatch(WalkNodeEvaluator.class),
                "and the predicate every reporting site shares must agree, or the banner announces a "
                    + "family that has been switched off");
        } finally {
            PathWeaverConfig.get().compatibilityTier = savedTier;
            SafetyGate.replaceDenials(savedDenials);
        }
    }

    /**
     * Zero means "keep dispatching", not "stop noticing".
     *
     * <p>The settings screen offers this as the benchmarking choice, and the trade it describes is
     * narrow: no trips. Silently also turning off the counting and the log would be a wider trade
     * than the one the user agreed to.
     */
    @Test
    void aLimitOfZeroNeverTripsButStillCounts() {
        PathWeaverConfig.get().workerFailureLimit = 0;
        for (int i = 0; i < 50; i++) fail(WalkNodeEvaluator.class);

        assertFalse(SafetyGate.isDeniedByRuntimeFailure(WalkNodeEvaluator.class),
            "a limit of zero must never switch a family off, at any failure count");
        assertEquals(50, WorkerFailureBreaker.windowedCount(WalkNodeEvaluator.class),
            "but the failures must still be counted, because the log and /pathweaver status are not "
                + "part of the trade the setting offers");
    }

    /** The cumulative ceiling catches the genuine slow leak the window is designed to ignore. */
    @Test
    void aSlowLeakStillTripsOnTheCumulativeCeiling() {
        for (int i = 0; i < WorkerFailureBreaker.CUMULATIVE_CEILING; i++) {
            WorkerFailureBreaker.setTick(i * 10_000L);
            fail(WalkNodeEvaluator.class);
        }
        assertTrue(SafetyGate.isDeniedByRuntimeFailure(WalkNodeEvaluator.class),
            "failures far enough apart never fill the window, so without a ceiling a family that "
                + "fails forever would dispatch forever");
    }

    /**
     * Reset is per SERVER, not per JVM, and a singleplayer client starts many servers in one JVM.
     *
     * <p>{@code EntityInstallSink.clear()} already re-arms its one-shot log flags for this exact
     * reason. A trip surviving into the next world would be worse than a stale counter: a
     * permanently inert movement family with no log line at all, because the one-shot report had
     * already burned in the previous world.
     */
    @Test
    void aTripDoesNotSurviveIntoTheNextWorld() {
        fail(WalkNodeEvaluator.class);
        fail(WalkNodeEvaluator.class);
        fail(WalkNodeEvaluator.class);
        assertTrue(SafetyGate.isDeniedByRuntimeFailure(WalkNodeEvaluator.class));

        WorkerFailureBreaker.reset();

        assertFalse(SafetyGate.isDeniedByRuntimeFailure(WalkNodeEvaluator.class),
            "starting a server must re-arm the family");
        assertEquals(0, WorkerFailureBreaker.windowedCount(WalkNodeEvaluator.class),
            "and the counter with it, or the next world trips early on the last one's failures");
    }

    /** An evaluator with no allowlisted ancestor is not this mechanism's business. */
    @Test
    void aFailureWithNoFamilyIsIgnoredRatherThanGuessedAt() {
        assertFalse(WorkerFailureBreaker.recordSearchFailure(String.class, new RuntimeException()),
            "no allowlisted ancestor means nothing to switch off");
        assertFalse(WorkerFailureBreaker.recordSearchFailure(null, new RuntimeException()),
            "and a null evaluator must not throw from inside a failure path");
    }

    /**
     * The breaker runs inside a failure path whose delivery side has no {@code catch} at all.
     *
     * <p>{@code ResultInstaller.drain} is {@code try/finally}, called from a Fabric tick event. A
     * throwable escaping here would not be a failed search, it would be a crashed server.
     */
    @Test
    void recordingAFailureNeverThrows() {
        Throwable hostile = new RuntimeException("hostile") {
            @Override public StackTraceElement[] getStackTrace() {
                throw new IllegalStateException("a throwable that will not describe itself");
            }
            @Override public String getMessage() {
                throw new IllegalStateException("nor name itself");
            }
        };
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(
            () -> WorkerFailureBreaker.recordSearchFailure(WalkNodeEvaluator.class, hostile),
            "a mechanism that cannot record a failure must never turn that failure into a crash");
    }

    /**
     * The diagnostic must name the runtime cause, not the scan's.
     *
     * <p>Without its own branch, a tripped family falls through {@code isAllowed} to
     * {@code evaluatorReason}, which explains a refusal as "whose family the compatibility scan
     * denied" — and the scan denied nothing. That sends an operator to edit {@code trustedMods} over
     * a problem no setting will fix, which is the invented-cause defect the last four review rounds
     * were all about.
     */
    @Test
    void theMobDiagnosticNamesTheRuntimeCauseRatherThanTheScan() {
        java.util.Set<Class<?>> savedDenials;
        synchronized (SafetyGate.deniedBySafety) {
            savedDenials = java.util.Set.copyOf(SafetyGate.deniedBySafety);
        }
        try {
            SafetyGate.replaceDenials(java.util.Set.of());
            fail(WalkNodeEvaluator.class);
            fail(WalkNodeEvaluator.class);
            fail(WalkNodeEvaluator.class);

            var verdict = dev.pathweaver.command.MobEligibility.of(
                net.minecraft.world.entity.Mob.class, WalkNodeEvaluator.class, null, true,
                dev.pathweaver.command.MobEligibility.LandRegistry.PERMITS);
            assertFalse(verdict.eligible(), "a tripped family is not eligible");
            assertTrue(verdict.reason().contains("threw on a worker"),
                "and the reason must be the one that happened: " + verdict.reason());
            assertFalse(verdict.reason().contains("scan"),
                "blaming the compatibility scan for a runtime trip sends the operator to a setting "
                    + "that cannot help: " + verdict.reason());
        } finally {
            SafetyGate.replaceDenials(savedDenials);
        }
    }

    /** Silence until something actually fails; a permanent "breaker: armed" line is noise. */
    @Test
    void nothingIsReportedUntilSomethingFails() {
        assertTrue(SafetyGate.runtimeFailureDenials().isEmpty(),
            "a healthy server must have nothing to report");
        fail(WalkNodeEvaluator.class);
        assertTrue(SafetyGate.runtimeFailureDenials().isEmpty(),
            "and one failure below the threshold is still not a trip -- reporting it as one would "
                + "tell an operator the mod had switched off while it is still dispatching");
    }

}
