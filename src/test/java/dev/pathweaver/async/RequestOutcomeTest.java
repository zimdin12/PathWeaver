package dev.pathweaver.async;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.pathweaver.PathWeaverRuntime;
import org.junit.jupiter.api.Test;

/**
 * Pins what "discarded" is allowed to mean.
 *
 * <p>It used to mean everything that was not an install, which put successful searches in a bucket
 * the mod published as wasted work. The two exclusions below are the whole point of the split, so
 * they are asserted rather than left to a comment somebody can quietly disagree with later.
 */
class RequestOutcomeTest {

    @Test
    void aSearchThatProvesNoRouteExistsIsNotWastedWork() {
        assertFalse(RequestOutcome.NO_PATH.isDiscard(),
            "the search ran to completion and returned a correct answer");
        assertFalse(RequestOutcome.INSTALLED.isDiscard());
    }

    @Test
    void admissionRefusalIsNotWastedWorkBecauseNothingWasDispatched() {
        assertFalse(RequestOutcome.POOL_SATURATED.isDiscard(),
            "vanilla ran that search synchronously; no worker time was spent");
    }

    @Test
    void everyOtherOutcomeCountsAsWastedWork() {
        for (RequestOutcome outcome : RequestOutcome.values()) {
            // Three exemptions, each for a stated reason: a path was installed; a search succeeded
            // with an empty answer; or nothing ever reached a worker so there was no work to waste.
            // SETUP_FAILED_PRE_DISPATCH is the third kind -- adding it as a discard reported
            // "dispatched=0 ... discarded=41028" for searches vanilla had already run synchronously.
            boolean exempt = outcome == RequestOutcome.INSTALLED
                || outcome == RequestOutcome.NO_PATH
                || outcome == RequestOutcome.POOL_SATURATED
                || outcome == RequestOutcome.SETUP_FAILED_PRE_DISPATCH
                // Nothing was computed, so nothing was thrown away.
                || outcome == RequestOutcome.BREAKER_OPEN;
            assertEquals(!exempt, outcome.isDiscard(), outcome + " is on the wrong side of the line");
        }
    }

    @Test
    void everyOutcomeDescribesItselfForTheDiagnostic() {
        for (RequestOutcome outcome : RequestOutcome.values()) {
            assertFalse(outcome.description().isBlank(), outcome + " has no description");
        }
    }

    @Test
    void theDiscardTotalExcludesSuccessfulEmptySearches() {
        PathWeaverRuntime runtime = PathWeaverRuntime.get();
        long discardedBefore = runtime.discardedCount();
        long installedBefore = runtime.installedCount();

        runtime.markOutcome(RequestOutcome.NO_PATH);
        runtime.markOutcome(RequestOutcome.POOL_SATURATED);
        assertEquals(discardedBefore, runtime.discardedCount(),
            "neither a proven-empty search nor a refused admission is a discard");

        runtime.markOutcome(RequestOutcome.ARRIVED_STALE);
        assertEquals(discardedBefore + 1, runtime.discardedCount());

        runtime.markOutcome(RequestOutcome.INSTALLED);
        assertEquals(installedBefore + 1, runtime.installedCount());
        assertTrue(runtime.outcomeCount(RequestOutcome.NO_PATH) > 0);
    }

    /**
     * Every outcome must state, deliberately, which side of both display rules it is on.
     *
     * <p>These were two hard-coded lists at the reporting site, and the second constant ever added
     * missed both — printing "dispatch setup failed before admission" in green next to `installed`
     * with a share of 136760%, because it was divided by a `dispatched` total it is not part of.
     * Enumerating here forces the choice when a constant is added instead of defaulting to wrong.
     */
    @Test
    void everyOutcomeDeclaresWhetherItIsPartOfTheDispatchedTotal() {
        for (RequestOutcome outcome : RequestOutcome.values()) {
            // SETUP_FAILED is NOT here: the mixin selects it only after markDispatched(), so it is
            // part of the dispatched total. This test previously enforced the opposite and so
            // actively defended the bug.
            boolean notInThisSessionsTotal = outcome == RequestOutcome.POOL_SATURATED
                || outcome == RequestOutcome.SETUP_FAILED_PRE_DISPATCH
                // Refused at the gate, before anything reaches a worker.
                || outcome == RequestOutcome.BREAKER_OPEN
                // Dispatched, but in the PREVIOUS session. onServerStarting zeroes `dispatched` and
                // the outcome array, then records one SERVER_RESET per leftover registration --
                // deliberately, because that is the only evidence leftovers existed. The numerator
                // therefore lands in this session's array while the denominator does not include it,
                // which is exactly the failure this test's own message names. The question here is
                // not "did it reach a worker" but "is it part of the total it is divided by".
                || outcome == RequestOutcome.SERVER_RESET;
            assertEquals(!notInThisSessionsTotal, outcome.countsAgainstDispatched(),
                outcome + " is on the wrong side of the dispatched-total line, so its percentage "
                    + "is measured against a total it does not belong to");
        }
    }

    @Test
    void onlyAnInstalledPathOrAProvenAbsenceIsGoodNews() {
        for (RequestOutcome outcome : RequestOutcome.values()) {
            boolean expected = outcome == RequestOutcome.INSTALLED
                || outcome == RequestOutcome.NO_PATH;
            assertEquals(expected, outcome.isGoodNews(),
                outcome + " is on the wrong side of the green/amber line; the footer tells the "
                    + "operator only amber rows are wasted work");
        }
        // Not the same question as isDiscard(): these two are neither wasted nor good.
        assertFalse(RequestOutcome.POOL_SATURATED.isDiscard());
        assertFalse(RequestOutcome.POOL_SATURATED.isGoodNews());
        assertFalse(RequestOutcome.SETUP_FAILED_PRE_DISPATCH.isDiscard());
        assertFalse(RequestOutcome.SETUP_FAILED_PRE_DISPATCH.isGoodNews());
    }

    /**
     * Both arms of the setup-failure choice, tested directly.
     *
     * <p>This replaces three bytecode contracts that were each bypassed by a differently-shaped
     * mutation. The decision is a pure function now, so there is nothing to stand beside.
     */
    @Test
    void aSetupFailureIsPreDispatchUntilItHasBeenCountedAsDispatched() {
        assertEquals(RequestOutcome.SETUP_FAILED_PRE_DISPATCH,
            RequestOutcome.setupFailure(RequestOutcome.DispatchStage.NOT_REGISTERED),
            "a failure before registration is also pre-dispatch");
        assertFalse(RequestOutcome.DispatchStage.NOT_REGISTERED.hasRegistered());
        assertTrue(RequestOutcome.DispatchStage.REGISTERED.hasRegistered(),
            "a registered request must be discarded through the sink, not merely counted");
        assertTrue(RequestOutcome.DispatchStage.DISPATCHED.hasRegistered(),
            "dispatched implies registered -- which is exactly why two booleans allowed the caller "
                + "to write (registered || dispatchCounted) and mean the wrong thing");
        assertEquals(RequestOutcome.SETUP_FAILED_PRE_DISPATCH, RequestOutcome.setupFailure(RequestOutcome.DispatchStage.REGISTERED),
            "a failure before markDispatched never reached a worker and must not count against the "
                + "dispatched total -- registration is not dispatch, since register precedes submit");
        assertEquals(RequestOutcome.SETUP_FAILED, RequestOutcome.setupFailure(RequestOutcome.DispatchStage.DISPATCHED),
            "a failure after markDispatched is part of the dispatched total");
        assertFalse(RequestOutcome.setupFailure(RequestOutcome.DispatchStage.REGISTERED).countsAgainstDispatched());
        assertTrue(RequestOutcome.setupFailure(RequestOutcome.DispatchStage.DISPATCHED).countsAgainstDispatched());
    }
}
