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

        // Asserted BEFORE the next failure, and that ordering is the test. Checking only that a
        // later failure reports again passes whether or not the reset happened, because the entry
        // from the previous world is still sitting there -- the assertion was satisfied by the very
        // state it was supposed to prove had been cleared.
        assertTrue(ModAttribution.REPORTS.isEmpty(),
            "the one-shot report must re-arm. Leaving it burnt makes the first failure of every "
                + "later world in the same JVM silent, which is the documented reason this reset is "
                + "per-server rather than per-JVM: " + ModAttribution.REPORTS);
        fail(WalkNodeEvaluator.class);
        assertTrue(ModAttribution.REPORTS.contains("first-failure:WalkNodeEvaluator"),
            "and the next world's first failure must be reported again: " + ModAttribution.REPORTS);
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


    /**
     * The log blocks must actually be emitted, which nothing checked.
     *
     * <p>Deleting {@code reportFirstFailure} and deleting {@code reportTrip} were each compiled by a
     * reviewer and each left the whole suite green. The first is described in this feature's own
     * design as "the half that pays on every install"; the second is the only thing that tells an
     * operator a movement family has been switched off. A feature whose entire user-visible output
     * can be removed without a test noticing does not have tests.
     */
    @Test
    void theFirstFailureAndTheTripAreBothReported() {
        fail(WalkNodeEvaluator.class);
        assertTrue(ModAttribution.REPORTS.contains("first-failure:WalkNodeEvaluator"),
            "the first failure of a family must be reported whether or not it ever trips: "
                + ModAttribution.REPORTS);

        fail(WalkNodeEvaluator.class);
        assertEquals(1, ModAttribution.REPORTS.stream()
                .filter(r -> r.startsWith("first-failure:")).count(),
            "and only once -- a storm of failures must not become a storm of log blocks");

        fail(WalkNodeEvaluator.class);
        assertTrue(ModAttribution.REPORTS.contains("trip:WalkNodeEvaluator:WINDOW:3"),
            "a trip must be announced, with the rule that fired and the count that reached it: "
                + ModAttribution.REPORTS);
    }

    /**
     * A limit above the cumulative ceiling must mean what it says.
     *
     * <p>{@code workerFailureLimit} accepts up to 1000 and anything above 25 tripped at 25 anyway,
     * with nothing in the tooltip, the status line or the log admitting it. This project's own
     * runtime warnings call that worse than a config that does what you asked and warns you.
     */
    @Test
    void aLimitAboveTheCeilingIsHonouredRatherThanSilentlyCapped() {
        PathWeaverConfig.get().workerFailureLimit = 40;
        PathWeaverConfig.get().workerFailureWindowTicks = 0;
        for (int i = 0; i < 39; i++) fail(WalkNodeEvaluator.class);
        assertFalse(SafetyGate.isDeniedByRuntimeFailure(WalkNodeEvaluator.class),
            "39 failures must not trip a limit of 40 just because an internal backstop is 25");
        fail(WalkNodeEvaluator.class);
        assertTrue(SafetyGate.isDeniedByRuntimeFailure(WalkNodeEvaluator.class),
            "and the 40th must");
    }

    /** The ceiling still catches the leak the window is designed to ignore, and says which rule. */
    @Test
    void theCeilingTripIsAnnouncedAsTheCeilingAndNotAsTheWindow() {
        for (int i = 0; i < WorkerFailureBreaker.CUMULATIVE_CEILING; i++) {
            WorkerFailureBreaker.setTick(i * 10_000L);
            fail(WalkNodeEvaluator.class);
        }
        assertTrue(ModAttribution.REPORTS.contains(
                "trip:WalkNodeEvaluator:CEILING:" + WorkerFailureBreaker.CUMULATIVE_CEILING),
            "naming the window threshold after a backstop trip sends an operator to raise a setting "
                + "that changes nothing, and reporting the window's count of 1 alongside it says a "
                + "single failure switched the family off: " + ModAttribution.REPORTS);
    }

    /**
     * A VM error is not evidence that a worker read something it should not have.
     *
     * <p>On a 200-mod server an {@code OutOfMemoryError} is the likeliest throwable a worker will ever
     * produce, it recurs in bursts, and counting three of them switches five movement families off
     * for the session while printing a block that blames whichever mods were on the frame.
     */
    @Test
    void aVirtualMachineErrorIsNeverCountedAsAnIncompatibility() {
        for (int i = 0; i < 10; i++) {
            WorkerFailureBreaker.recordSearchFailure(WalkNodeEvaluator.class,
                new OutOfMemoryError("synthetic"));
        }
        assertFalse(SafetyGate.isDeniedByRuntimeFailure(WalkNodeEvaluator.class),
            "the JVM being in trouble is not an incompatibility, and switching the mod off for it "
                + "is a false trip with an invented culprit");
        assertEquals(0, WorkerFailureBreaker.windowedCount(WalkNodeEvaluator.class),
            "and it must not be counted toward one either");
    }

    /**
     * A straggler worker from the previous server must not switch a family off in the next one.
     *
     * <p>{@code reset()} runs on the main thread at server start while the old pool may still be
     * draining -- {@code shutdownNow()} does not wait. A trip that lands after the reset gives the
     * new world an inert movement family with no log line, because the one-shot report already fired
     * in the world before. That is the exact outcome the reset exists to prevent.
     */
    @Test
    void aTripFromTheServerThatJustStoppedDoesNotFollowIntoTheNextOne() {
        fail(WalkNodeEvaluator.class);
        fail(WalkNodeEvaluator.class);
        WorkerFailureBreaker.reset();
        // The third failure belongs to the server that has already stopped.
        fail(WalkNodeEvaluator.class);
        assertFalse(SafetyGate.isDeniedByRuntimeFailure(WalkNodeEvaluator.class),
            "a failure counted before the reset must not combine with one after it, and a verdict "
                + "reached for the old server must not be installed into the new one");
    }


    /**
     * The family rule, asserted directly rather than through a trip.
     *
     * <p>Reducing {@code allowlistedFamilyOf} to "first match wins" survived every test, because
     * {@code WalkNodeEvaluator} happens to be declared first in the allowlist. The rule was pinned by
     * declaration order, not by the rule, and reordering one {@code List.of} would have silently
     * multiplied the configured threshold by four.
     */
    @Test
    void everyLandFamilyResolvesToWalkAndSwimResolvesToItself() {
        assertEquals(WalkNodeEvaluator.class,
            SafetyGate.allowlistedFamilyOf(FlyNodeEvaluator.class));
        assertEquals(WalkNodeEvaluator.class,
            SafetyGate.allowlistedFamilyOf(AmphibiousNodeEvaluator.class));
        assertEquals(WalkNodeEvaluator.class,
            SafetyGate.allowlistedFamilyOf(WalkNodeEvaluator.class));
        assertEquals(SwimNodeEvaluator.class,
            SafetyGate.allowlistedFamilyOf(SwimNodeEvaluator.class),
            "swim derives from nothing else and must be its own family");
        assertEquals(null, SafetyGate.allowlistedFamilyOf(String.class),
            "and something unrelated must resolve to nothing rather than to whatever came first");
    }

    /**
     * The rule, not the declaration order.
     *
     * <p>"First match wins" gives the same answer as "most general wins" for every real input, purely
     * because {@code WalkNodeEvaluator} is declared first — so the test above passes under both and
     * proves nothing about the rule. Handing in the candidates in the opposite order is what tells
     * them apart, and it is what stops a future edit to one {@code List.of} silently multiplying the
     * configured threshold by the number of land families.
     */
    @Test
    void theMostGeneralFamilyWinsWhateverOrderTheCandidatesArriveIn() {
        java.util.List<Class<?>> reversed = java.util.List.of(
            FlyNodeEvaluator.class, AmphibiousNodeEvaluator.class, WalkNodeEvaluator.class);
        assertEquals(WalkNodeEvaluator.class,
            SafetyGate.allowlistedFamilyOf(FlyNodeEvaluator.class, reversed),
            "Fly is listed first and still must not win: it executes Walk's code, and counting "
                + "against it would file one failure per subclass for one shared bug");
        assertEquals(WalkNodeEvaluator.class,
            SafetyGate.allowlistedFamilyOf(AmphibiousNodeEvaluator.class, reversed));
    }

    /** A mod evaluator admitted at the unsafe tier still counts against the code it runs. */
    @Test
    void aModSubclassResolvesToTheFamilyWhoseCodeItExecutes() {
        assertEquals(WalkNodeEvaluator.class,
            SafetyGate.allowlistedFamilyOf(ThirdPartyWalkEvaluator.class));
    }

    private static final class ThirdPartyWalkEvaluator extends WalkNodeEvaluator {}


    /** A ceiling trip must report the session total, not the window's count of one. */
    @Test
    void theCeilingTripReportsTheCountThatActuallyReachedIt() {
        for (int i = 0; i < WorkerFailureBreaker.CUMULATIVE_CEILING; i++) {
            WorkerFailureBreaker.setTick(i * 10_000L);
            fail(WalkNodeEvaluator.class);
        }
        assertEquals(WorkerFailureBreaker.CUMULATIVE_CEILING,
            WorkerFailureBreaker.cumulativeCount(WalkNodeEvaluator.class),
            "the session total is what crossed the backstop");
        assertEquals(1, WorkerFailureBreaker.windowedCount(WalkNodeEvaluator.class),
            "and the window is on its own -- printing this number for a ceiling trip would tell an "
                + "operator one failure had switched a family off");
    }

    /**
     * A trip is announced once, however many failures follow it.
     *
     * <p>{@code tripRuntimeFailure} returning the transition rather than the state is what makes the
     * whole block one-shot; without it every later failure re-emits it, and a pack with a real
     * incompatibility fills the log with the same eight lines.
     */
    @Test
    void aTripIsAnnouncedOnceEvenWhileFailuresKeepArriving() {
        for (int i = 0; i < 12; i++) fail(WalkNodeEvaluator.class);
        assertEquals(1, ModAttribution.REPORTS.stream().filter(r -> r.startsWith("trip:")).count(),
            "one trip, one block: " + ModAttribution.REPORTS);
    }

    /**
     * A straggler from the previous server must not install its verdict into the next one.
     *
     * <p>Pinned on the stamp rather than on the counter reset, because the counter reset alone
     * satisfies the coarser version of this test and left the stamp free to be deleted.
     */
    @Test
    void aVerdictReachedBeforeAResetIsNotInstalledAfterIt() {
        PathWeaverConfig.get().workerFailureLimit = 1;
        // Enough to trip, but the reset lands between the count and the install.
        long before = WorkerFailureBreaker.generationForTesting();
        WorkerFailureBreaker.reset();
        assertTrue(WorkerFailureBreaker.generationForTesting() != before,
            "reset must advance the generation, or nothing downstream can tell the servers apart");
        assertFalse(WorkerFailureBreaker.recordSearchFailureForGeneration(
                WalkNodeEvaluator.class, new IllegalStateException("from the old server"), before),
            "a failure stamped with the previous server's generation must not switch a family off "
                + "in this one");
        assertFalse(SafetyGate.isDeniedByRuntimeFailure(WalkNodeEvaluator.class));
    }

}
