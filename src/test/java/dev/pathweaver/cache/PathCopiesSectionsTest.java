package dev.pathweaver.cache;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which world sections a stored route depends on.
 *
 * <p>Getting this wrong is asymmetric. Cover too much and a valid route is thrown away, which costs
 * a cache hit. Cover too little and a mob is served a route through a block that has since become a
 * wall, which is a behaviour change nobody can reproduce. So the cover is checked in both
 * directions, and the tight direction is the one that matters.
 */
class PathCopiesSectionsTest {

    private static Path pathAlong(int fromX, int toX, int y, int z) {
        List<Node> nodes = new ArrayList<>();
        for (int x = fromX; x <= toX; x++) nodes.add(new Node(x, y, z));
        return new Path(nodes, new BlockPos(toX, y, z), true);
    }

    private static boolean covers(long[] sections, int blockX, int blockY, int blockZ) {
        long wanted = SectionPos.asLong(SectionPos.blockToSectionCoord(blockX),
            SectionPos.blockToSectionCoord(blockY), SectionPos.blockToSectionCoord(blockZ));
        return Arrays.stream(sections).anyMatch(section -> section == wanted);
    }

    @Test
    void theSectionsTheRouteRunsThroughAreCovered() {
        // A route entirely inside one section, deliberately away from a boundary, so this asserts
        // the obvious case and nothing else can make it pass.
        long[] sections = PathCopies.sectionsOf(pathAlong(4, 11, 68, 4));
        assertTrue(covers(sections, 4, 68, 4));
        assertTrue(covers(sections, 11, 68, 4));
    }

    /**
     * Whether a node is walkable depends on more than the node's own block: the block underneath it
     * carries the mob, the blocks above it are its headroom, and a corner it cuts is diagonal to it.
     * A route hugging a section boundary therefore depends on the section next door, and a cover
     * built only from the nodes' own sections would miss it.
     */
    @Test
    void theSectionNextDoorIsCoveredWhenTheRouteRunsAlongTheBoundary() {
        long[] sections = PathCopies.sectionsOf(pathAlong(16, 20, 68, 8));
        assertTrue(covers(sections, 15, 68, 8),
            "a block one step outside the route's own section was not covered");
        assertTrue(covers(sections, 16, 66, 8), "the ground under the route was not covered");
    }

    /**
     * A reach of zero must mean one offset, not a loop that never advances.
     *
     * <p>The cover was built with {@code for (int d = -reach; d <= reach; d += reach)}. At zero the
     * induction variable never moves, so the loop never ends. It cannot happen at the shipped
     * constant, which is why nobody saw it, and the symptom when it did happen was a thread at 100%
     * with no exception and no log line: a test JVM spun for forty minutes and the build simply
     * stopped producing results. This pins the arithmetic rather than the constant, because the
     * constant is not what was wrong.
     */
    @Test
    void aReachOfZeroSamplesOneOffsetInsteadOfLoopingForever() {
        assertArrayEquals(new int[] {0}, PathCopies.axisOffsets(0));
        assertArrayEquals(new int[] {0}, PathCopies.axisOffsets(-1));
        assertArrayEquals(new int[] {-2, 0, 2}, PathCopies.axisOffsets(2));
    }

    @Test
    void aSectionTheRouteNeverApproachesIsNotCovered() {
        // The negative control. A cover that returned every section would satisfy both tests above
        // while making the cache invalidate on any block change anywhere, which is indistinguishable
        // from the feature being off.
        long[] sections = PathCopies.sectionsOf(pathAlong(4, 11, 68, 4));
        assertFalse(covers(sections, 4, 68, 200));
        assertFalse(covers(sections, 300, 68, 4));
    }
}
