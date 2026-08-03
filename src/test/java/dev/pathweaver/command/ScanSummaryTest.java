package dev.pathweaver.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import net.minecraft.world.level.pathfinder.SwimNodeEvaluator;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import org.junit.jupiter.api.Test;

/**
 * The status line must describe what the tier did, not what the scan found.
 *
 * <p>Found by running the command on a real 222-mod pack rather than by reading it: at the unsafe
 * tier it reported all six movement families as denied and "running on the server thread", while
 * that same server was dispatching all six and had installed a thousand paths. Every number
 * underneath it was correct; only the sentence a human reads was wrong.
 *
 * <p>That is the second diagnostic in this release to contradict the code it describes, so the rule
 * is pinned here rather than left to care.
 */
class ScanSummaryTest {

    private static final Set<Class<?>> DENIED =
        Set.of(WalkNodeEvaluator.class, SwimNodeEvaluator.class);

    private static String joined(List<String> lines) {
        return String.join(" | ", lines);
    }

    @Test
    void nothingDeniedReadsAsActive() {
        String text = joined(PathWeaverCommand.scanSummary(Set.of(), false));
        assertTrue(text.contains("no movement family is denied"), text);
    }

    @Test
    void deniedAndNotWaivedSaysThoseSearchesAreSynchronous() {
        String text = joined(PathWeaverCommand.scanSummary(DENIED, false));
        assertTrue(text.contains("denied"), text);
        assertTrue(text.contains("run on the server thread"), text);
        assertTrue(text.contains("WalkNodeEvaluator"), text);
    }

    @Test
    void deniedButWaivedMustNotClaimThoseSearchesAreSynchronous() {
        String text = joined(PathWeaverCommand.scanSummary(DENIED, true));
        // The exact sentence that was wrong on a live server.
        assertFalse(text.contains("run on the server thread"),
            "the tier waived these denials, so the searches are running off-thread: " + text);
        assertTrue(text.contains("running anyway"), text);
        assertTrue(text.contains("Unsafe"), text);
        assertTrue(text.contains("WalkNodeEvaluator"),
            "an operator still needs to know which families are being run unchecked: " + text);
    }

    @Test
    void waivingNothingIsStillReportedAsActiveRatherThanAsAWaiver() {
        String text = joined(PathWeaverCommand.scanSummary(Set.of(), true));
        assertTrue(text.contains("no movement family is denied"), text);
        assertFalse(text.contains("running anyway"),
            "with nothing denied there is nothing to waive, and saying otherwise invites the "
                + "operator to think the tier is doing something: " + text);
    }

    /**
     * A failed scan must outrank the tier in the report, because it outranks it in the code.
     *
     * <p>{@code ForeignMixinScanner} only clears the denial set when {@code failed() == 0}, so at the
     * shipped {@code UNSAFE} default with one scan error the true state is "every family
     * synchronous, mod inert". Deriving the summary from the tier alone printed "running anyway,
     * because the tier is Unsafe" — inventing a risk the operator is not taking while concealing that
     * they installed something doing nothing. Both halves are wrong and they point opposite ways.
     */
    @Test
    void aFailedScanIsReportedAsRefusalEvenAtTheWaivingTier() {
        List<String> lines = PathWeaverCommand.scanSummary(
            List.of(WalkNodeEvaluator.class, SwimNodeEvaluator.class), true, true);
        String text = String.join(" | ", lines);
        assertTrue(text.contains("FAILED"), "the scan failure must lead: " + text);
        assertTrue(text.contains("server thread"),
            "the operator must be told the searches are synchronous: " + text);
        assertFalse(text.contains("running anyway"),
            "a failed scan is not waived by any tier, so this must not claim otherwise: " + text);
        assertFalse(text.contains("Keep backups"),
            "do not warn about a risk that is not being taken: " + text);
    }

    /**
     * "Nothing is denied" must not be reported as "everything can run".
     *
     * <p>Dispatch refuses every {@code WalkNodeEvaluator}-derived family — five of the six — while
     * Fabric's land path-type registry is unverified. This line reported all-clear straight through
     * that, while {@code /pathweaver mobs} reported the opposite, so the mod's own diagnostics
     * contradicted each other on the exact question an operator asks first.
     */
    @Test
    void nothingDeniedStillReportsFamiliesThatCannotDispatch() {
        String text = joined(PathWeaverCommand.scanSummary(
            Set.of(), false, false, List.of("WalkNodeEvaluator", "FlyNodeEvaluator")));
        assertTrue(text.contains("cannot dispatch"),
            "a family dispatch would refuse must be reported even when no mod is blamed: " + text);
        assertTrue(text.contains("WalkNodeEvaluator") && text.contains("FlyNodeEvaluator"),
            "the report must name them: " + text);
        assertFalse(text.contains("no movement family is denied"),
            "the all-clear line must not appear alongside families that cannot dispatch: " + text);
    }

    @Test
    void nothingDeniedAndEverythingDispatchableIsStillTheAllClear() {
        String text = joined(PathWeaverCommand.scanSummary(Set.of(), false, false, List.of()));
        assertTrue(text.contains("no movement family is denied"),
            "with nothing denied and nothing undispatchable this must stay the all-clear: " + text);
    }

    /**
     * The production input, which the formatting tests above do not exercise.
     *
     * <p>They pass their own list, so they pin the branch and not the value status actually reports.
     * That is the same shape as the bug this release fixed — a predicate covered on one side and
     * duplicated on the other — so the producer gets pinned to the same predicate the banner uses.
     */
    @Test
    void theProducedListAgreesWithTheDispatchPredicateItReports() {
        List<String> produced = PathWeaverCommand.undispatchableFamilyNames();
        List<String> expected = new java.util.ArrayList<>();
        for (Class<?> family : dev.pathweaver.gate.SafetyGate.allowlisted()) {
            if (!dev.pathweaver.gate.SafetyGate.canDispatch(family)) {
                expected.add(family.getSimpleName());
            }
        }
        assertEquals(expected, produced,
            "status must report exactly the families canDispatch refuses, or the banner and status "
                + "are answering different questions again");
        assertTrue(dev.pathweaver.gate.SafetyGate.allowlisted().size() >= produced.size(),
            "cannot report more undispatchable families than exist");
    }
}
