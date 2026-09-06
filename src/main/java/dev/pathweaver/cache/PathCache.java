package dev.pathweaver.cache;

import dev.pathweaver.async.RequestKey;
import net.minecraft.world.level.pathfinder.Path;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Routes one mob has already computed, offered to the next mob that asks the same question.
 *
 * <p>Main thread only, from end to end. Lookup happens at dispatch, the entry is remembered against
 * its request key there, and it is filled when the worker's result is drained. All three are on the
 * server thread, so nothing here needs to be concurrent, except {@link SectionChangeClock}, whose
 * writer is the world.
 *
 * <h2>What has to be true for a hit to be honest</h2>
 * <ol>
 *   <li>Every input to the search agrees. That is {@link PathCacheKey}, which enumerates the reads
 *       the vanilla evaluators make rather than guessing at them.</li>
 *   <li>The mob is standing in exactly the same place. The key compares blocks; this compares
 *       coordinates, because the evaluators read the mob's real position.</li>
 *   <li>The route is younger than {@code maxAgeTicks}.</li>
 *   <li>No block has changed along the route since the search ran. {@link SectionChangeClock}.</li>
 * </ol>
 * Fail any of them and the mob searches, exactly as it would have without this class.
 *
 * <h2>The one thing this cannot know</h2>
 * A block changed OUTSIDE the route's own neighbourhood can open a shorter way through, and a served
 * route will not take it. Vanilla does not react to that either, since its own invalidation only
 * fires for changes near the path a mob is already walking. So this is not a new kind of staleness,
 * but it is real and worth saying rather than implying the answer is identical.
 */
public final class PathCache {

    /** Dispatched searches whose result may be cacheable, keyed by the request they belong to. */
    private final Map<RequestKey, Pending> pending = new LinkedHashMap<>();
    private final Map<PathCacheKey, CachedPath> entries;
    private final SectionChangeClock clock = new SectionChangeClock();
    private final Counters counters = new Counters();
    private final int maxEntries;

    /**
     * The key a dispatch built, and the tick it read the world on.
     *
     * <p>The tick is kept here rather than recovered at completion because the search is anchored to
     * when it started. A result that lands three ticks later still describes the world of the tick it
     * was dispatched on.
     */
    private record Pending(PathCacheKey key, long dispatchTick,
                           long exactXBits, long exactYBits, long exactZBits) { }

    public PathCache(int maxEntries) {
        this.maxEntries = Math.max(1, maxEntries);
        this.entries = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<PathCacheKey, CachedPath> eldest) {
                return size() > PathCache.this.maxEntries;
            }
        };
    }

    /** A block changed. Called for every world block update, so it must stay a few instructions. */
    public void noteBlockChange(int dimensionHash, long sectionKey, long tick) {
        clock.recordChange(dimensionHash, sectionKey, tick);
    }

    /**
     * Is there a route for this exact question?
     *
     * @param serve false to measure without changing what any mob does
     */
    public CacheLookup lookup(PathCacheKey key, long xBits, long yBits, long zBits,
                              long tick, int maxAgeTicks, boolean serve) {
        counters.lookups++;
        CachedPath cached = entries.get(key);
        if (cached == null) return CacheLookup.MISS;
        if (tick - cached.computedTick() > maxAgeTicks || tick < cached.computedTick()) {
            counters.expired++;
            entries.remove(key);
            return CacheLookup.MISS;
        }
        if (!clock.unchangedSince(key.dimensionHash(), cached.sections(), cached.computedTick())) {
            counters.terrainChanged++;
            entries.remove(key);
            return CacheLookup.MISS;
        }
        if (!cached.samePosition(xBits, yBits, zBits)) {
            counters.blockOnlyHits++;
            return CacheLookup.BLOCK_ONLY;
        }
        if (!serve) {
            counters.wouldServe++;
            return CacheLookup.WOULD_SERVE;
        }
        counters.served++;
        return CacheLookup.served(PathCopies.deepCopy(cached.path()));
    }

    /** Main thread, at dispatch: this request's answer may be worth keeping. */
    public void remember(RequestKey requestKey, PathCacheKey key, long dispatchTick,
                         long xBits, long yBits, long zBits) {
        // Bounded by the pool's own admission limit in normal operation. The guard is for the
        // abnormal case: a request that never reaches drain leaks its entry, and an unbounded map of
        // leaked entries is a slow memory fault rather than a lost cache hit.
        if (pending.size() >= maxEntries) pending.clear();
        pending.put(requestKey, new Pending(key, dispatchTick, xBits, yBits, zBits));
    }

    /** Main thread, when a result is drained: keep it if the world stood still while it ran. */
    public void completed(RequestKey requestKey, Path path) {
        Pending waiting = pending.remove(requestKey);
        if (waiting == null || path == null || path.getNodeCount() == 0) return;
        long[] sections = PathCopies.sectionsOf(path);
        // The search read live chunks while it ran. If anything along the route changed at or after
        // the tick it started, the route may already describe a world that no longer exists, and
        // caching it would hand that to every later mob rather than to this one alone.
        if (!clock.unchangedSince(waiting.key().dimensionHash(), sections, waiting.dispatchTick())) {
            counters.refusedTerrainMoved++;
            return;
        }
        counters.stored++;
        entries.put(waiting.key(), new CachedPath(PathCopies.deepCopy(path), waiting.dispatchTick(),
            sections, waiting.exactXBits(), waiting.exactYBits(), waiting.exactZBits()));
    }

    /** Main thread: this request will never produce a cacheable answer. */
    public void forget(RequestKey requestKey) {
        pending.remove(requestKey);
    }

    /** A new world, or a stopped server: nothing learned about the old one applies. */
    public void clear() {
        pending.clear();
        entries.clear();
        clock.clear();
        counters.reset();
    }

    public int size() { return entries.size(); }

    public Counters counters() { return counters; }

    /**
     * What the cache did, in the terms an operator needs to decide whether to switch it on.
     *
     * <p>Every field is printed even when zero. A cache reporting no hits and a cache whose key is
     * subtly wrong produce the same silence otherwise, and the second is the likelier of the two.
     */
    public static final class Counters {
        public long lookups;
        public long served;
        public long wouldServe;
        public long blockOnlyHits;
        public long expired;
        public long terrainChanged;
        public long stored;
        public long refusedTerrainMoved;

        void reset() {
            lookups = served = wouldServe = blockOnlyHits = 0L;
            expired = terrainChanged = stored = refusedTerrainMoved = 0L;
        }

        /** Hits that were, or could have been, served: the number the feature is judged on. */
        public long usableHits() { return served + wouldServe; }
    }
}
