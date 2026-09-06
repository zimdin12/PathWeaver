package dev.pathweaver.cache;

import net.minecraft.world.level.pathfinder.Path;

/**
 * What a cache lookup found, and what may be done with it.
 *
 * <p>Three outcomes rather than a nullable path, because "there was nothing" and "there was
 * something we chose not to serve" are different facts and the second one is the measurement the
 * shadow mode exists to produce.
 */
public record CacheLookup(Kind kind, Path path) {

    public enum Kind {
        /** No usable entry: never searched from here, or too old, or the terrain changed. */
        MISS,
        /**
         * An entry matched on every input including the mob's exact position, but the cache is not
         * serving. This is the number that says what turning it on would be worth.
         */
        WOULD_SERVE,
        /**
         * An entry matched every input except the mob's exact coordinates, which agreed only on the
         * block. Never served, in either mode. Counted because it says what a looser key would buy,
         * which is the one design change worth measuring before anyone attempts it.
         */
        BLOCK_ONLY,
        /** Served: {@link #path()} is this mob's own copy of the earlier route. */
        SERVED
    }

    public static final CacheLookup MISS = new CacheLookup(Kind.MISS, null);
    public static final CacheLookup WOULD_SERVE = new CacheLookup(Kind.WOULD_SERVE, null);
    public static final CacheLookup BLOCK_ONLY = new CacheLookup(Kind.BLOCK_ONLY, null);

    public static CacheLookup served(Path path) {
        return new CacheLookup(Kind.SERVED, path);
    }

    public boolean isServed() { return kind == Kind.SERVED; }
}
