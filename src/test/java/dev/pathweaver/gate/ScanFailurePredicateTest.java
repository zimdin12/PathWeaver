package dev.pathweaver.gate;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The predicate that decides whether anything can be waived, driven by a real decision.
 *
 * <p>It had no test at all. {@code ScanSummaryTest} passes {@code scanFailed} as a literal argument,
 * so it pins the formatting branch and never the producer — the same "one hop from the call site"
 * shape that has now produced a blocker twice. Replacing the producer with {@code return false}
 * survived the whole suite, and on the shipped UNSAFE default that would make all three diagnostics
 * report "running anyway, because the tier is Unsafe" while the mod was inert.
 */
class ScanFailurePredicateTest {

    @Test
    void aScanThatRecordedAFailureReadsAsFailed() {
        ForeignMixinScanner.ScanDecision decision =
            ForeignMixinScanner.decide(List.of(), List.of("could not read a mixin config"));
        assertTrue(decision.failed() > 0, "fixture precondition: the decision must record a failure");
        assertTrue(ForeignMixinScanner.scanFailed(decision),
            "a scan that could not complete must read as failed, or the tier appears to waive "
                + "denials it cannot waive");
    }

    @Test
    void aCleanScanDoesNotReadAsFailed() {
        ForeignMixinScanner.ScanDecision decision = ForeignMixinScanner.decide(List.of(), List.of());
        assertFalse(ForeignMixinScanner.scanFailed(decision),
            "a scan with no failures must not be reported as failed");
    }

    @Test
    void aFailedScanDeniesEveryFamilySoNothingCanDispatch() {
        ForeignMixinScanner.ScanDecision decision =
            ForeignMixinScanner.decide(List.of(), List.of("boom"));
        assertTrue(decision.denied().containsAll(SafetyGate.allowlisted()),
            "a failed scan must fail closed across every family: " + decision.denied());
    }
}
