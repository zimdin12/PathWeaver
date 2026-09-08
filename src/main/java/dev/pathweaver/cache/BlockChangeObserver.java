package dev.pathweaver.cache;

import dev.pathweaver.config.PathWeaverConfig;

/**
 * The decision behind every block update: record this change for the route cache, or ignore it.
 *
 * <p>This used to live inside the mixin, where nothing could execute it. A test can read a mixin and
 * argue about its bytecode; it cannot run one without a server, so the rule that decides whether the
 * world is being watched was the one rule in the cache with no executed coverage. It is a plain
 * static function of its arguments now, and the mixin is an adapter that supplies them and decides
 * nothing.
 *
 * <p>Both switches silence it. With the mod off nothing should touch the world, and with the cache
 * off there is nothing to keep the records for. That is why {@link PathCache} needs a policy barrier:
 * routes stored earlier outlive a period when this returns without recording, so a block broken
 * during that period leaves no trace, and serving such a route afterwards would walk a mob through
 * terrain nobody watched.
 *
 * <p>The caller evaluates the section key and tick before the gate rather than after, which costs a
 * few field reads on every block update in a world where the mod is switched off. That is the price
 * of the gate being executable, and it is smaller than the virtual call the hook already makes.
 */
public final class BlockChangeObserver {

    private BlockChangeObserver() { }

    /**
     * @param serverRunning the runtime's own state; a level can publish updates while the mod's
     *                      runtime is stopped, and the cache must not be written then
     */
    public static void observe(PathWeaverConfig config, boolean serverRunning, PathCache cache,
                               int dimensionHash, long sectionKey, long tick) {
        if (config == null || cache == null) return;
        if (!config.recordsBlockChanges()) return;
        if (!serverRunning) return;
        cache.noteBlockChange(dimensionHash, sectionKey, tick);
    }
}
