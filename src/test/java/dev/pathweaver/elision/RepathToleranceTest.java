package dev.pathweaver.elision;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RepathToleranceTest {
    @Test void extremeCoordinatesCannotOverflowIntoTolerance() {
        BlockPos low = new BlockPos(Integer.MIN_VALUE, 0, 0);
        BlockPos high = new BlockPos(Integer.MAX_VALUE, 0, 0);
        RepathTolerance.CurrentPath current = new RepathTolerance.CurrentPath(
            low, low, true, false, true, false, 0);
        assertNull(RepathTolerance.reusableTarget(Set.of(high), current, 0, 1));
    }

    @Test void acceptsOnlyOneTargetThatMatchesEndpointAndTolerance() {
        RepathTolerance.CurrentPath current = new RepathTolerance.CurrentPath(
            new BlockPos(10, 64, 10), new BlockPos(10, 64, 10),
            true, false, true, false, 0);
        BlockPos matched = new BlockPos(11, 64, 10);
        Set<BlockPos> requested = Set.of(matched, new BlockPos(50, 64, 50));
        assertEquals(matched, RepathTolerance.reusableTarget(requested, current, 0, 1));

        RepathTolerance.CurrentPath mismatchedTargets = new RepathTolerance.CurrentPath(
            new BlockPos(10, 64, 10), new BlockPos(50, 64, 50),
            true, false, true, false, 0);
        assertNull(RepathTolerance.reusableTarget(requested, mismatchedTargets, 0, 1),
            "one target may not satisfy tolerance while another satisfies endpoint validity");
    }

    @Test void rejectsInvalidNavigationFacts() {
        Set<BlockPos> targets = Set.of(new BlockPos(1, 0, 0));
        BlockPos origin = new BlockPos(0, 0, 0);
        List<RepathTolerance.CurrentPath> invalid = List.of(
            new RepathTolerance.CurrentPath(origin, origin, false, false, true, false, 0),
            new RepathTolerance.CurrentPath(origin, origin, true, true, true, false, 0),
            new RepathTolerance.CurrentPath(origin, origin, true, false, false, false, 0),
            new RepathTolerance.CurrentPath(origin, origin, true, false, true, true, 0),
            new RepathTolerance.CurrentPath(origin, origin, true, false, true, false, 1));
        for (RepathTolerance.CurrentPath current : invalid) {
            assertNull(RepathTolerance.reusableTarget(targets, current, 0, 1), current.toString());
        }
    }

    @Test void reusableTargetSelectionIsDeterministicAcrossEquivalentCandidates() {
        BlockPos origin = new BlockPos(0, 0, 0);
        RepathTolerance.CurrentPath current = new RepathTolerance.CurrentPath(
            origin, origin, true, false, true, false, 0);
        assertEquals(new BlockPos(-1, 0, 0), RepathTolerance.reusableTarget(
            Set.of(new BlockPos(1, 0, 0), new BlockPos(-1, 0, 0)), current, 0, 1));
    }

    @Test void endpointMustRemainWithinRequestedReachPlusExplicitTolerance() {
        BlockPos pathTarget = new BlockPos(0, 0, 0);
        RepathTolerance.CurrentPath tooFar = new RepathTolerance.CurrentPath(
            pathTarget, new BlockPos(-2, 0, 0), true, false, true, false, 0);
        assertNull(RepathTolerance.reusableTarget(
            Set.of(new BlockPos(1, 0, 0)), tooFar, 0, 1));

        RepathTolerance.CurrentPath withinCombinedBound = new RepathTolerance.CurrentPath(
            pathTarget, new BlockPos(-1, 0, 0), true, false, true, false, 1);
        assertNotNull(RepathTolerance.reusableTarget(
            Set.of(new BlockPos(1, 0, 0)), withinCombinedBound, 1, 1));
    }
}
