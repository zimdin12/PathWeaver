package dev.pathweaver.cache;

import dev.pathweaver.async.RequestKey;
import dev.pathweaver.async.RequestTarget;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PathCacheTest {

    private static final Object DIMENSION = "overworld";
    private static final long X = Double.doubleToLongBits(10.5);
    private static final long Y = Double.doubleToLongBits(64.0);
    private static final long Z = Double.doubleToLongBits(10.5);

    private static PathCacheKey key() {
        return new PathCacheKey(DIMENSION, 10, 64, 10,
            RequestTarget.of(Set.of(new BlockPos(20, 64, 10)), 8, false, 1, 16.0f),
            String.class, Integer.class, 0, 4096, Float.floatToIntBits(1.0f),
            Float.floatToIntBits(0.6f), 3, Float.floatToIntBits(0.6f),
            Float.floatToIntBits(1.95f), 0, new int[] {1});
    }

    private static Path straightPath(int length) {
        List<Node> nodes = new ArrayList<>();
        for (int i = 0; i < length; i++) nodes.add(new Node(10 + i, 64, 10));
        return new Path(nodes, new BlockPos(10 + length - 1, 64, 10), true);
    }

    private static RequestKey request(long token) {
        return new RequestKey(1L, token, 7);
    }

    /** Fills the cache with one route dispatched at {@code tick} and returns the cache. */
    private static PathCache cacheHolding(Path path, long tick) {
        PathCache cache = new PathCache(64);
        cache.remember(request(1L), key(), tick, X, Y, Z);
        cache.completed(request(1L), path);
        return cache;
    }

    @Test
    void aRouteStoredForOneRequestAnswersTheNextIdenticalOne() {
        // Positive control for everything below: if this fails, every "did not serve" assertion in
        // this file is passing for the wrong reason.
        PathCache cache = cacheHolding(straightPath(5), 100L);
        CacheLookup found = cache.lookup(key(), X, Y, Z, 110L, 40, true);
        assertTrue(found.isServed());
        assertEquals(5, found.path().getNodeCount());
        assertEquals(1L, cache.counters().served);
    }

    /**
     * The reason a shared cache needs a real copy rather than vanilla's {@code Path.copy()}.
     *
     * <p>{@code GroundPathNavigation.trimPath} calls {@code truncateNodes}, which is
     * {@code subList(n, size).clear()} on the path's node list, and vanilla's constructor stores that
     * list by reference. Two mobs holding "copies" of one cached route would share the list, so the
     * first skeleton to trim its route in daylight would cut every other mob's route to the same
     * length. This is the assertion that would go red.
     */
    @Test
    void oneMobTruncatingItsRouteLeavesTheOtherAndTheCachedOriginalIntact() {
        PathCache cache = cacheHolding(straightPath(5), 100L);
        Path first = cache.lookup(key(), X, Y, Z, 101L, 40, true).path();
        Path second = cache.lookup(key(), X, Y, Z, 101L, 40, true).path();
        assertNotSame(first, second);

        first.truncateNodes(2);
        first.advance();
        first.replaceNode(0, new Node(999, 999, 999));

        assertEquals(5, second.getNodeCount(), "the other mob's route was cut short");
        assertEquals(0, second.getNextNodeIndex(), "the other mob's progress was advanced");
        assertEquals(10, second.getNode(0).x, "the other mob's first node was overwritten");
        assertEquals(5, cache.lookup(key(), X, Y, Z, 102L, 40, true).path().getNodeCount(),
            "the cached route itself was damaged by a mob that held a copy of it");
    }

    @Test
    void nodesAreCopiedRatherThanShared() {
        PathCache cache = cacheHolding(straightPath(3), 100L);
        Path first = cache.lookup(key(), X, Y, Z, 101L, 40, true).path();
        Path second = cache.lookup(key(), X, Y, Z, 101L, 40, true).path();
        assertNotSame(first.getNode(0), second.getNode(0));
        assertEquals(first.getNode(0).x, second.getNode(0).x);
    }

    /**
     * The route's predecessor chain must point inside the copy, never back at the cache's own nodes,
     * or a mob that walked its {@code cameFrom} chain would reach objects another mob also holds.
     */
    @Test
    void predecessorLinksAreRemappedOntoTheCopy() {
        List<Node> nodes = new ArrayList<>();
        for (int i = 0; i < 3; i++) nodes.add(new Node(10 + i, 64, 10));
        nodes.get(1).cameFrom = nodes.get(0);
        nodes.get(2).cameFrom = nodes.get(1);
        Path source = new Path(nodes, new BlockPos(12, 64, 10), true);

        Path copy = PathCopies.deepCopy(source);
        assertSame(copy.getNode(0), copy.getNode(1).cameFrom);
        assertSame(copy.getNode(1), copy.getNode(2).cameFrom);
        assertNotSame(nodes.get(0), copy.getNode(1).cameFrom);
    }

    @Test
    void aBlockChangedAlongTheRouteWithdrawsIt() {
        PathCache cache = cacheHolding(straightPath(5), 100L);
        cache.noteBlockChange(DIMENSION.hashCode(),
            SectionPos.asLong(new BlockPos(12, 64, 10)), 105L);
        assertFalse(cache.lookup(key(), X, Y, Z, 110L, 40, true).isServed());
        assertEquals(1L, cache.counters().terrainChanged);
    }

    /**
     * The negative control for the test above. Invalidation that fires for every change would pass
     * it while making the cache useless, and the two are indistinguishable from the hit rate alone.
     */
    @Test
    void aBlockChangedFarFromTheRouteLeavesItServable() {
        PathCache cache = cacheHolding(straightPath(5), 100L);
        cache.noteBlockChange(DIMENSION.hashCode(),
            SectionPos.asLong(new BlockPos(10, 64, 400)), 105L);
        assertTrue(cache.lookup(key(), X, Y, Z, 110L, 40, true).isServed());
    }

    @Test
    void aRouteThatIsOlderThanTheAgeLimitIsNotServed() {
        PathCache cache = cacheHolding(straightPath(5), 100L);
        assertTrue(cache.lookup(key(), X, Y, Z, 140L, 40, true).isServed());
        PathCache other = cacheHolding(straightPath(5), 100L);
        assertFalse(other.lookup(key(), X, Y, Z, 141L, 40, true).isServed());
        assertEquals(1L, other.counters().expired);
    }

    /**
     * A search whose ground moved while it ran may already have read blocks that no longer exist.
     * Installing that on the one mob that asked is vanilla's own risk; keeping it and handing it to
     * every later mob is this cache's, and is not one worth taking.
     */
    @Test
    void aResultWhoseGroundMovedDuringTheSearchIsNotKept() {
        PathCache cache = new PathCache(64);
        cache.remember(request(1L), key(), 100L, X, Y, Z);
        cache.noteBlockChange(DIMENSION.hashCode(),
            SectionPos.asLong(new BlockPos(12, 64, 10)), 101L);
        cache.completed(request(1L), straightPath(5));
        assertEquals(0L, cache.counters().stored);
        assertEquals(1L, cache.counters().refusedTerrainMoved);
        assertFalse(cache.lookup(key(), X, Y, Z, 102L, 40, true).isServed());
    }

    @Test
    void shadowModeCountsTheHitAndServesNothing() {
        PathCache cache = cacheHolding(straightPath(5), 100L);
        CacheLookup found = cache.lookup(key(), X, Y, Z, 110L, 40, false);
        assertEquals(CacheLookup.Kind.WOULD_SERVE, found.kind());
        assertEquals(1L, cache.counters().wouldServe);
        assertEquals(0L, cache.counters().served);
    }

    /**
     * The mob is standing in the same block but not in the same spot. The evaluators read the mob's
     * real coordinates, so this is a miss; it is counted separately because that count is the whole
     * evidence for whether a looser key would be worth designing.
     */
    @Test
    void sameBlockButADifferentSpotIsCountedAndNotServed() {
        PathCache cache = cacheHolding(straightPath(5), 100L);
        CacheLookup found = cache.lookup(key(), Double.doubleToLongBits(10.7), Y, Z,
            110L, 40, true);
        assertEquals(CacheLookup.Kind.BLOCK_ONLY, found.kind());
        assertEquals(0L, cache.counters().served);
        assertEquals(1L, cache.counters().blockOnlyHits);
    }

    @Test
    void theLeastRecentlyUsedRouteIsDroppedOnceTheCacheIsFull() {
        PathCache cache = new PathCache(1);
        cache.remember(request(1L), key(), 100L, X, Y, Z);
        cache.completed(request(1L), straightPath(3));
        PathCacheKey other = new PathCacheKey(DIMENSION, 99, 64, 10, key().target(),
            String.class, Integer.class, 0, 4096, Float.floatToIntBits(1.0f),
            Float.floatToIntBits(0.6f), 3, Float.floatToIntBits(0.6f),
            Float.floatToIntBits(1.95f), 0, new int[] {1});
        cache.remember(request(2L), other, 100L, X, Y, Z);
        cache.completed(request(2L), straightPath(3));
        assertEquals(1, cache.size());
        assertFalse(cache.lookup(key(), X, Y, Z, 101L, 40, true).isServed());
    }

    @Test
    void aRequestThatNeverProducedARouteLeavesNothingBehind() {
        PathCache cache = new PathCache(64);
        cache.remember(request(1L), key(), 100L, X, Y, Z);
        cache.forget(request(1L));
        cache.completed(request(1L), straightPath(5));
        assertEquals(0, cache.size());
    }
}
