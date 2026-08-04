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
                || outcome == RequestOutcome.SETUP_FAILED_PRE_DISPATCH;
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
            boolean neverDispatched = outcome == RequestOutcome.POOL_SATURATED
                || outcome == RequestOutcome.SETUP_FAILED_PRE_DISPATCH;
            assertEquals(!neverDispatched, outcome.countsAgainstDispatched(),
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
}
