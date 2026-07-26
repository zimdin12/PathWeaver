package dev.pathweaver.duck;

import dev.pathweaver.async.NavigationIdentity;
import net.minecraft.world.level.pathfinder.Path;

/**
 * Duck-typed interface implemented by {@code PathNavigation} via mixin, so non-mixin code
 * (the install sink) can drive install/staleness without touching mixin internals directly.
 */
public interface PWNavigation {
    /**
     * Main thread: install an async-computed path using vanilla's own moveTo bookkeeping, then replay
     * the tail of vanilla {@code createPath} (targetPos / reachRange / resetStuckTimeout). Callback
     * completion is owned centrally by the request registration so install exceptions are also balanced.
     */
    void pathweaver$install(Path path);

    /** Main thread: true if the owning mob is gone or has moved too far from the dispatch position. */
    boolean pathweaver$stale(double dispatchX, double dispatchY, double dispatchZ);

    /** Main thread: current entity/world/navigation/path/intent identity for exact install validation. */
    NavigationIdentity pathweaver$identity();

    /**
     * Main thread: fire {@code mob.onPathfindingDone()} iff a matching {@code onPathfindingStart} was
     * fired at dispatch and not yet balanced. Idempotent, so install-vs-discard both call it safely.
     */
    void pathweaver$onPathfindingDone();

    /**
     * Main thread: undo the optimistic {@code targetPos} write made at dispatch.
     *
     * <p>Dispatch sets {@code targetPos} before a path exists so recompute and repath reuse keep
     * working during the in-flight window. Every route that does not install a path must undo it,
     * otherwise {@code targetPos} names the new target while {@code path} still holds the previous,
     * unrelated path — a pairing vanilla never produces. The next request for that target would
     * then match vanilla's reuse short-circuit, hand back the stale path and report success, so the
     * mob walks to the old destination while callers believe it is heading to the new one.
     *
     * <p>Restores only when {@code targetPos} is still exactly the value dispatch wrote, so a
     * newer request that has already claimed it is never clobbered.
     */
    void pathweaver$rollbackOptimisticTarget();

    /**
     * Main thread: abandon a path installation that threw part-way through.
     *
     * <p>Installation calls vanilla {@code moveTo(path, speed)}, which foreign mixins can inject
     * into. Such an injection can throw <em>after</em> vanilla has already set the path, so the
     * navigation may hold a new or partially-applied path when the failure surfaces. Restoring only
     * the target would then pair that path with the previous target — the same mismatched invariant
     * the rollback exists to prevent, merely inverted.
     *
     * <p>This clears the path outright and restores the pre-dispatch target, so the navigation is
     * left in a state vanilla can produce and the next request recomputes from scratch. Routes that
     * never touched the path (no-path, discard, supersede, staleness) must keep using
     * {@link #pathweaver$rollbackOptimisticTarget()} instead, which preserves the existing path.
     */
    void pathweaver$abortFailedInstall();
}
