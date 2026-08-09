package dev.pathweaver.duck;

import dev.pathweaver.async.NavigationIdentity;
import net.minecraft.world.level.pathfinder.Path;

/**
 * Duck-typed interface implemented by {@code PathNavigation} via mixin, so non-mixin code
 * (the install sink) can drive install/staleness without touching mixin internals directly.
 */
public interface PWNavigation {
    /**
     * Main thread: mark that a genuine movement request is starting, and bind its speed.
     *
     * <p>Exists for subclasses of {@code PathNavigation} that OVERRIDE a movement entry point rather
     * than inherit it. A mixin transforms only its target class, so the inject on
     * {@code PathNavigation.moveTo(Entity, double)} never ran for {@code WallClimberNavigation}'s
     * override, and spiders resolved every chase path synchronously while the mod reported them as
     * eligible.
     */
    void pathweaver$beginMovementRequest(double speed);

    /**
     * Main thread: open and close the window in which an inner {@code createPath} counts as a genuine
     * movement request rather than a query.
     *
     * <p>Capturing the speed is not enough on its own. Dispatch keys on this depth, and the base
     * mixin raises it with a {@code @WrapOperation} around the {@code createPath} call sites inside
     * {@code PathNavigation}'s own movement methods. A subclass that overrides one of those methods
     * makes the call from its own body, which no wrap covers — so the request looked like a
     * query-only path lookup and stayed synchronous. A game test caught exactly that.
     */
    void pathweaver$enterMovementRequest();

    void pathweaver$exitMovementRequest();

    /**
     * Main thread: take the "this dispatch was accepted" flag, so an overriding movement method can
     * report success the way the base one does.
     *
     * <p>The base mixin forces {@code true} at the RETURN of {@code PathNavigation}'s three
     * {@code moveTo} overloads. {@code WallClimberNavigation} overrides one of them, so that inject
     * never ran for spiders — and an accepted dispatch could still report FAILURE to its caller when
     * the navigation's previous path was finished: vanilla's {@code moveTo(Path, double)} returns
     * false for a done path, and {@code MeleeAttackGoal} answers a false with
     * {@code ticksUntilNextPathRecalculation += 15}. A spider whose search was accepted got a
     * fifteen-tick chase stall no other mob type gets.
     */
    boolean pathweaver$consumeAcceptedDeferred();

    /**
     * Main thread: install an async-computed path using vanilla's own moveTo bookkeeping, then replay
     * the tail of vanilla {@code createPath} (targetPos / reachRange / resetStuckTimeout). Callback
     * completion is owned centrally by the request registration so install exceptions are also
     * balanced.
     */
    void pathweaver$install(Path path);

    /** Main thread: true if the owning mob is gone or has moved too far from the dispatch position. */
    boolean pathweaver$stale(double dispatchX, double dispatchY, double dispatchZ);

    /** Main thread: current entity/world/navigation/path/intent identity for exact install validation. */
    NavigationIdentity pathweaver$identity();

    
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
