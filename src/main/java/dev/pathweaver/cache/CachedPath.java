package dev.pathweaver.cache;

import net.minecraft.world.level.pathfinder.Path;

/**
 * One route, kept so a later identical request need not search for it again.
 *
 * <p>{@code path} is the cache's private copy and is never handed to a mob; every hit gets its own
 * {@link PathCopies#deepCopy(Path)}. Vanilla navigation mutates the path object it holds, so a
 * shared instance would let one mob truncate another's route.
 *
 * <p>It is null while the cache is only measuring. Everything that decides whether a hit is honest
 * is still here -- the tick, the sections, the exact position -- so the count means the same thing;
 * only the route nobody was going to ask for is missing.
 *
 * <p>{@code computedTick} is the tick the search was DISPATCHED, not the tick it landed. The search
 * read the world as it stood then, so that is the moment the route's claim about terrain is anchored
 * to. Using the landing tick would treat changes made while the search was in flight as if they had
 * happened before it.
 *
 * <p>{@code exact*Bits} are the mob's real coordinates. The key holds only the block, so these are
 * what separates a reuse that is exactly right from one that is merely close; see {@link PathCache}.
 */
public record CachedPath(Path path, long computedTick, long[] sections,
                         long exactXBits, long exactYBits, long exactZBits) {

    public boolean samePosition(long xBits, long yBits, long zBits) {
        return exactXBits == xBits && exactYBits == yBits && exactZBits == zBits;
    }
}
