package dev.pathweaver.cache;

import net.minecraft.core.SectionPos;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Copying a {@link Path} so two mobs can hold the same route without sharing one object.
 *
 * <h2>Why vanilla's own {@code Path.copy()} cannot be used</h2>
 * It is shallow. The constructor stores the node list by reference and {@code copy()} passes that
 * same reference on, so both "copies" share one list of shared nodes. Navigation then mutates it:
 * {@code GroundPathNavigation.trimPath} calls {@code truncateNodes}, which is
 * {@code subList(n, size).clear()} on the shared list, and {@code PathNavigation.trimPath} calls
 * {@code replaceNode}, which is {@code list.set(...)}. Both are on the ordinary ground-navigation
 * path that most mobs take every tick.
 *
 * <p>The truncation is also per-mob: it fires when the navigation's {@code avoidSun} is set and a
 * node can see sky, which is a skeleton's state, not a villager's. So handing one path object to two
 * mobs does not merely risk corruption in theory; the most common mob in the game would cut another
 * mob's route short.
 *
 * <p>Hence a real copy: a new list, a new {@link Node} per entry, and no {@code debugData}.
 */
public final class PathCopies {

    /** Blocks around a node whose change can invalidate it: the node, its support, its headroom. */
    private static final int NODE_INFLUENCE_BLOCKS = 2;

    /**
     * The offsets to sample on each axis, derived from the reach rather than stepped towards it.
     *
     * <p>This was written as {@code for (int d = -reach; d <= reach; d += reach)}, which is an
     * infinite loop when the reach is zero: the induction variable never moves and the condition
     * never fails. Nothing in the shipped configuration produces a zero, which is exactly why it
     * survived review -- the symptom is one thread pinned at 100% with no exception, no log line and
     * no output, and it happens on the server thread, inside a method whose whole job is to be
     * cheap. It cost forty minutes of a stalled build to find, from a thread dump.
     *
     * <p>Deriving the offsets removes the arithmetic that could produce it. A reach of zero now
     * means one offset, which is what "no influence beyond the node" should have meant all along.
     */
    static final int[] AXIS_OFFSETS = axisOffsets(NODE_INFLUENCE_BLOCKS);

    static int[] axisOffsets(int reach) {
        return reach <= 0 ? new int[] {0} : new int[] {-reach, 0, reach};
    }

    private PathCopies() { }

    /**
     * A path that shares nothing mutable with {@code source}.
     *
     * <p>Nodes are copied with vanilla's own {@code cloneAndMove}, so the field list stays correct
     * when a game update adds one, rather than being a hand-written list that silently stops copying
     * something. It copies {@code cameFrom} as a reference to the ORIGINAL node, which is remapped
     * here onto the corresponding copy, or dropped when the predecessor is not part of this path.
     *
     * <p>{@code debugData} is deliberately left null. That is a state vanilla itself produces and
     * both readers handle: {@code Mob}'s debug-value supplier null-checks it before use, and
     * {@code Path.writeToStream} is only reached through that supplier. The cost is that the
     * pathfinding debug renderer shows no open/closed set for a reused route, and the benefit is
     * that the cache does not retain the thousands of nodes a search visited.
     */
    public static Path deepCopy(Path source) {
        int count = source.getNodeCount();
        List<Node> copies = new ArrayList<>(count);
        Map<Node, Node> byOriginal = new IdentityHashMap<>(count);
        for (int i = 0; i < count; i++) {
            Node original = source.getNode(i);
            Node copy = original.cloneAndMove(original.x, original.y, original.z);
            byOriginal.put(original, copy);
            copies.add(copy);
        }
        for (Node copy : copies) {
            copy.cameFrom = copy.cameFrom == null ? null : byOriginal.get(copy.cameFrom);
        }
        Path copy = new Path(copies, source.getTarget(), source.canReach());
        copy.setNextNodeIndex(source.getNextNodeIndex());
        return copy;
    }

    /**
     * The world sections whose contents this route depends on.
     *
     * <p>Not just the sections the nodes sit in. Whether a node is walkable depends on the block
     * under it, the blocks its hitbox occupies and the corners it may cut, so a change up to
     * {@value #NODE_INFLUENCE_BLOCKS} blocks away can invalidate it. Each node therefore contributes
     * its own section plus the sections of the eight corners of that neighbourhood, which is the
     * cheapest cover that cannot miss one.
     *
     * <p>Over-covering costs a lost cache hit. Under-covering serves a mob a route through a wall.
     */
    public static long[] sectionsOf(Path path) {
        long[] sections = new long[16];
        int size = 0;
        int count = path.getNodeCount();
        for (int i = 0; i < count; i++) {
            Node node = path.getNode(i);
            for (int dx : AXIS_OFFSETS) {
                for (int dy : AXIS_OFFSETS) {
                    for (int dz : AXIS_OFFSETS) {
                        long key = SectionPos.asLong(
                            SectionPos.blockToSectionCoord(node.x + dx),
                            SectionPos.blockToSectionCoord(node.y + dy),
                            SectionPos.blockToSectionCoord(node.z + dz));
                        if (contains(sections, size, key)) continue;
                        if (size == sections.length) {
                            long[] grown = new long[size * 2];
                            System.arraycopy(sections, 0, grown, 0, size);
                            sections = grown;
                        }
                        sections[size++] = key;
                    }
                }
            }
        }
        long[] exact = new long[size];
        System.arraycopy(sections, 0, exact, 0, size);
        return exact;
    }

    // Linear, because a route spans a handful of sections and the 27 candidates each node offers are
    // almost all duplicates of the one before. A hash set here would allocate more than it saves.
    private static boolean contains(long[] values, int size, long value) {
        for (int i = 0; i < size; i++) {
            if (values[i] == value) return true;
        }
        return false;
    }
}
