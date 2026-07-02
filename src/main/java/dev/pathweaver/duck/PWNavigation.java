package dev.pathweaver.duck;

import net.minecraft.world.level.pathfinder.Path;

/**
 * Duck-typed interface implemented by {@code PathNavigation} via mixin, so non-mixin code
 * (the install sink) can drive install/staleness without touching mixin internals directly.
 */
public interface PWNavigation {
    /** Main thread: install an async-computed path using vanilla's own moveTo bookkeeping. */
    void pathweaver$install(Path path);

    /** Main thread: true if the owning mob is gone or has moved too far from the dispatch position. */
    boolean pathweaver$stale(double dispatchX, double dispatchY, double dispatchZ);
}
