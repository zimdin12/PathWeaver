package dev.pathweaver.duck;

import net.minecraft.world.level.pathfinder.Path;

/**
 * Duck-typed interface implemented by {@code PathNavigation} via mixin, so non-mixin code
 * (the install sink) can drive install/staleness without touching mixin internals directly.
 */
public interface PWNavigation {
    /**
     * Main thread: install an async-computed path using vanilla's own moveTo bookkeeping, then replay
     * the tail of vanilla {@code createPath} (targetPos / reachRange / resetStuckTimeout) and fire the
     * balanced {@code onPathfindingDone} for the callback started at dispatch.
     */
    void pathweaver$install(Path path);

    /** Main thread: true if the owning mob is gone or has moved too far from the dispatch position. */
    boolean pathweaver$stale(double dispatchX, double dispatchY, double dispatchZ);

    /**
     * Main thread: fire {@code mob.onPathfindingDone()} iff a matching {@code onPathfindingStart} was
     * fired at dispatch and not yet balanced. Idempotent, so install-vs-discard both call it safely.
     */
    void pathweaver$onPathfindingDone();
}
