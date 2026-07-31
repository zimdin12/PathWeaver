package dev.pathweaver.command;

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
}
