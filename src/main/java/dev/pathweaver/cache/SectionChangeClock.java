package dev.pathweaver.cache;

import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Last tick on which a block changed in each 16x16x16 world section.
 *
 * <p>This is the only thing that makes a shared path cache defensible. A cached route is a claim
 * about terrain, and terrain changes: a player walls a doorway, a creeper opens one, farmland is
 * trampled, lava flows. Serving a route computed before such a change is not a slower mob, it is a
 * mob walking into a wall — a behaviour change, not a performance one. Vanilla never faces this
 * because it recomputes from live blocks every time.
 *
 * <h2>Fixed table, deliberately</h2>
 * The obvious structure is a map from section to tick, which grows without limit as players explore:
 * a long-running server would accumulate hundreds of thousands of entries for sections no cached path
 * has ever touched. Instead this is a fixed array of {@value #SLOTS} slots and a section is hashed
 * into one.
 *
 * <p>Two sections can therefore share a slot. The consequence is that a change in one is read as a
 * change in the other, so a still-valid cached path is discarded. That is a lost cache hit and
 * nothing else — the collision can only ever say "changed", never "unchanged". A guard that degrades
 * into refusing is a guard; one that degrades into permitting is decoration.
 *
 * <h2>Ticks, not a revision counter</h2>
 * A counter would have to be sampled at dispatch, before the path exists, which means sampling the
 * whole searched region — several hundred sections per request — and carrying it through the
 * in-flight window. Recording the tick of the last change instead lets both questions be asked after
 * the fact, against only the sections the finished path actually crosses:
 * <ul>
 *   <li>store: did anything change here at or after the tick the search was dispatched? Then the
 *       search may already have read stale blocks and its result is not cacheable.</li>
 *   <li>serve: did anything change here since the search ran? Then the route is not trustworthy.</li>
 * </ul>
 */
public final class SectionChangeClock {

    /** 65,536 slots, 512 KB. Sized so an ordinary play session's active sections rarely collide. */
    private static final int SLOTS = 1 << 16;
    private static final int MASK = SLOTS - 1;

    /**
     * "Nothing has ever changed here", and it cannot be zero.
     *
     * <p>An {@code AtomicLongArray} starts at zero, and zero is also a real game tick: the first one.
     * With zero as the sentinel, a search dispatched on tick 0 asks "did anything change at or after
     * tick 0" and every untouched slot answers yes, so nothing computed at world start is ever
     * cacheable. Caught by the test that drives a result through the installer with a dispatch tick
     * of zero, which is what a fixture naturally uses and what a fresh world actually runs.
     */
    private static final long NEVER = -1L;

    /**
     * Atomic because the writer is not provably alone. Vanilla calls {@code sendBlockUpdated} on the
     * server thread, but chunk-generation mods move world writes onto their own threads, and a torn
     * long read here would answer a safety question with a value that was never written.
     */
    private final AtomicLongArray lastChangedTick = new AtomicLongArray(SLOTS);

    public SectionChangeClock() {
        clear();
    }

    /** Section coordinates repeat across dimensions, so the dimension has to be part of the slot. */
    private static int slot(int dimensionHash, long sectionKey) {
        long z = sectionKey * 0x9E3779B97F4A7C15L + dimensionHash * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return (int) ((z ^ (z >>> 31)) & MASK);
    }

    /** A block in this section changed on {@code tick}. */
    public void recordChange(int dimensionHash, long sectionKey, long tick) {
        int index = slot(dimensionHash, sectionKey);
        long previous = lastChangedTick.get(index);
        // Monotonic: a slot shared by two sections must report the more recent of the two changes,
        // and a lower tick arriving late must not walk the slot backwards into "nothing happened".
        while (tick > previous && !lastChangedTick.compareAndSet(index, previous, tick)) {
            previous = lastChangedTick.get(index);
        }
    }

    /** True when nothing has changed in any of these sections since {@code sinceTick} (exclusive). */
    public boolean unchangedSince(int dimensionHash, long[] sectionKeys, long sinceTick) {
        for (long sectionKey : sectionKeys) {
            if (lastChangedTick.get(slot(dimensionHash, sectionKey)) >= sinceTick) return false;
        }
        return true;
    }

    /** Forget every recorded change. Called when a server starts, so a new world starts clean. */
    public void clear() {
        for (int i = 0; i < SLOTS; i++) lastChangedTick.set(i, NEVER);
    }
}
