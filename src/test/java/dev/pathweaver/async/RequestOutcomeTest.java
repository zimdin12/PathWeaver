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
            boolean exempt = outcome == RequestOutcome.INSTALLED
                || outcome == RequestOutcome.NO_PATH
                || outcome == RequestOutcome.POOL_SATURATED;
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
}
