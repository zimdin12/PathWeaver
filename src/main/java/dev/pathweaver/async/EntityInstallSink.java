package dev.pathweaver.async;

import dev.pathweaver.PathWeaver;
import dev.pathweaver.config.PathWeaverConfig;
import dev.pathweaver.duck.PWNavigation;
import dev.pathweaver.gate.FabricLandPathRegistryLatch;
import net.minecraft.world.level.pathfinder.Path;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/**
 * Main-thread bridge from request-keyed async completions to live navigation. Installation requires
 * the exact request key plus unchanged entity UUID, world/dimension, navigation, path and target intent.
 */
public class EntityInstallSink implements ResultInstaller.InstallSink {
    public enum PendingDecision { NONE, PRESERVE, SUPERSEDE }

    private record Registration(RequestKey key, PWNavigation navigation,
                                NavigationIdentity identity, RequestTarget target,
                                boolean requiresEmptyLandRegistry) { }

    private static final RequestTarget UNSPECIFIED_TARGET =
        RequestTarget.of(Set.of(), 0, false, 0, 0.0F);
    private final Map<Integer, Registration> inFlight = new ConcurrentHashMap<>();
    private final Map<Integer, Long> failUntilTick = new ConcurrentHashMap<>();
    private final AtomicBoolean callbackFailureLogged = new AtomicBoolean();
    private final AtomicBoolean rollbackFailureLogged = new AtomicBoolean();
    private final BooleanSupplier landRegistryAllowsInstall;
    /** Tick at which expired cooldown entries were last swept, so the map cannot grow forever. */
    private long lastCooldownSweepTick;
    private final AtomicBoolean installFailureLogged = new AtomicBoolean();
    private static final long FAIL_COOLDOWN_TICKS = 40L;
    private static final long COOLDOWN_SWEEP_INTERVAL_TICKS = 20L;
    private volatile long currentTick;

    public EntityInstallSink() {
        this(FabricLandPathRegistryLatch::allowsWalkInstall);
    }

    /** Test seam accepts an isolated ordering model and cannot reset production lifecycle state. */
    EntityInstallSink(BooleanSupplier landRegistryAllowsInstall) {
        this.landRegistryAllowsInstall = java.util.Objects.requireNonNull(landRegistryAllowsInstall);
    }

    public void setTick(long tick) { this.currentTick = tick; }

    /** Called from the interceptor on the main thread at dispatch time. */
    public void register(RequestKey key, PWNavigation navigation, RequestTarget target) {
        register(key, navigation, target, false);
    }

    /** Capture whether this exact request depends on Fabric's land-provider registry staying empty. */
    public void register(RequestKey key, PWNavigation navigation, RequestTarget target,
                         boolean requiresEmptyLandRegistry) {
        Registration next = new Registration(
            key, navigation, navigation.pathweaver$identity(), target, requiresEmptyLandRegistry);
        Registration existing = inFlight.putIfAbsent(key.entityId(), next);
        if (existing != null) {
            throw new IllegalStateException("Entity " + key.entityId()
                + " already has an accepted async path registration");
        }
    }

    /** Package-private helper for tests whose target identity is irrelevant. */
    void register(RequestKey key, PWNavigation navigation) {
        register(key, navigation, UNSPECIFIED_TARGET);
    }

    public boolean isRegistered(int entityId) {
        return inFlight.containsKey(entityId);
    }

    public boolean isRegistered(int entityId, PWNavigation navigation) {
        Registration registration = inFlight.get(entityId);
        return registration != null && registration.navigation() == navigation;
    }

    public PendingDecision pendingDecision(int entityId, PWNavigation navigation, RequestTarget target) {
        return pendingDecision(entityId, navigation, target, false);
    }

    public PendingDecision pendingDecision(int entityId, PWNavigation navigation, RequestTarget target,
                                           boolean recomputeInvalidated) {
        Registration registration = inFlight.get(entityId);
        if (registration == null) return PendingDecision.NONE;
        if (recomputeInvalidated) return PendingDecision.SUPERSEDE;
        if (registration.navigation() != navigation) return PendingDecision.SUPERSEDE;
        try {
            if (!registration.identity().sameLiveIdentity(navigation.pathweaver$identity())) {
                return PendingDecision.SUPERSEDE;
            }
        } catch (Throwable ignored) {
            return PendingDecision.SUPERSEDE;
        }
        return registration.target().equals(target)
            ? PendingDecision.PRESERVE : PendingDecision.SUPERSEDE;
    }

    /** Cancel the current exact navigation request because a materially different intent replaced it. */
    public boolean supersede(int entityId) {
        Registration registration = inFlight.get(entityId);
        if (registration == null || !inFlight.remove(entityId, registration)) return false;
        finishDiscard(registration);
        return true;
    }

    /** Stop may invalidate only the registration owned by that exact navigation object. */
    public boolean cancel(int entityId, PWNavigation navigation) {
        Registration registration = inFlight.get(entityId);
        if (registration == null || registration.navigation() != navigation
                || !inFlight.remove(entityId, registration)) return false;
        finishDiscard(registration);
        return true;
    }

    private void finishDiscard(Registration registration) {
        rollbackOptimisticTarget(registration);
        finishCallback(registration);
        dev.pathweaver.PathWeaverRuntime.get().markDiscarded();
    }

    /**
     * Undo the optimistic targetPos written at dispatch. Every route that reaches here ended
     * without installing a path, so leaving it in place would pair the new target with the old
     * path and make vanilla's reuse short-circuit hand back a stale path forever.
     */
    /** Install threw: clear any partially-applied path AND restore the pre-dispatch target. */
    private void abortFailedInstall(Registration registration) {
        try {
            registration.navigation().pathweaver$abortFailedInstall();
        } catch (Throwable abortFailure) {
            if (rollbackFailureLogged.compareAndSet(false, true)) {
                try {
                    PathWeaver.LOG.warn("Aborting a failed path installation threw; the request was "
                        + "still discarded.", abortFailure);
                } catch (Throwable ignored) {
                    // Cancellation stays terminal even if the logging backend is compromised.
                }
            }
        }
    }

    private void rollbackOptimisticTarget(Registration registration) {
        try {
            registration.navigation().pathweaver$rollbackOptimisticTarget();
        } catch (Throwable rollbackFailure) {
            if (rollbackFailureLogged.compareAndSet(false, true)) {
                try {
                    PathWeaver.LOG.warn("Restoring the pre-dispatch navigation target threw; "
                        + "cancellation continued.", rollbackFailure);
                } catch (Throwable ignored) {
                    // Cancellation stays terminal even if the logging backend is compromised.
                }
            }
        }
    }

    private void finishCallback(Registration registration) {
        try {
            registration.navigation().pathweaver$onPathfindingDone();
        } catch (Throwable callbackFailure) {
            if (callbackFailureLogged.compareAndSet(false, true)) {
                try {
                    PathWeaver.LOG.warn("A mod callback threw while cancelling async pathfinding; "
                        + "the request was discarded and cancellation continued.", callbackFailure);
                } catch (Throwable ignored) {
                    // Cancellation must remain terminal even if the logging backend is compromised.
                }
            }
        }
    }

    /** Test seam: number of live sync-cooldown entries. */
    int cooldownEntryCount() { return failUntilTick.size(); }

    public boolean shouldForceSync(int entityId, long tick) {
        sweepExpiredCooldowns(tick);
        Long until = failUntilTick.get(entityId);
        if (until == null) return false;
        if (tick >= until) {
            failUntilTick.remove(entityId);
            return false;
        }
        return true;
    }

    /**
     * Drop cooldown entries whose deadline has passed.
     *
     * <p>An entry was previously removed only if that same entity asked again. A mob that failed a
     * search and then died, despawned or changed dimension never asks again, and entity ids are not
     * reused within a run, so on a long-lived server the map grew without bound. Sweeping is cheap
     * because the map is normally empty and is only walked once per second of server time.
     */
    private void sweepExpiredCooldowns(long tick) {
        if (failUntilTick.isEmpty() || tick - lastCooldownSweepTick < COOLDOWN_SWEEP_INTERVAL_TICKS) {
            return;
        }
        lastCooldownSweepTick = tick;
        failUntilTick.entrySet().removeIf(entry -> tick >= entry.getValue());
    }

    private Registration matching(RequestKey key) {
        Registration registration = inFlight.get(key.entityId());
        return registration != null && registration.key().equals(key) ? registration : null;
    }

    @Override
    public boolean isStale(RequestKey key, long dispatchTick, double x, double y, double z) {
        Registration registration = matching(key);
        if (registration == null) return true;
        if (registration.requiresEmptyLandRegistry() && !landRegistryAllowsInstall.getAsBoolean()) {
            return true;
        }
        long age = currentTick - dispatchTick;
        if (age < 0L || age > PathWeaverConfig.get().maxResultAgeTicks) return true;
        try {
            return !registration.identity().sameLiveIdentity(
                        registration.navigation().pathweaver$identity())
                || registration.navigation().pathweaver$stale(x, y, z);
        } catch (Throwable ignored) {
            return true;
        }
    }

    @Override
    public void install(RequestKey key, Path path) {
        Registration registration = matching(key);
        if (registration != null && inFlight.remove(key.entityId(), registration)) {
            try {
                registration.navigation().pathweaver$install(path);
                failUntilTick.remove(key.entityId());
                dev.pathweaver.PathWeaverRuntime.get().markInstalled();
            } catch (Throwable installFailure) {
                // Installation calls vanilla moveTo, which foreign mixins can inject into, so a
                // throw here may leave a new or partially-applied path behind. Restoring only the
                // target would pair that path with the old target — the same broken invariant.
                // Abort clears the path and restores the target together.
                abortFailedInstall(registration);
                failUntilTick.put(key.entityId(), currentTick + FAIL_COOLDOWN_TICKS);
                dev.pathweaver.PathWeaverRuntime.get().markDiscarded();
                if (installFailureLogged.compareAndSet(false, true)) {
                    try {
                        PathWeaver.LOG.warn("Async path installation failed; the request was discarded "
                            + "and later requests temporarily run sync.", installFailure);
                    } catch (Throwable ignored) {
                        // Callback balance and failure cooldown must survive a broken logging backend.
                    }
                }
            } finally {
                finishCallback(registration);
            }
        }
    }

    @Override
    public void discard(RequestKey key) {
        Registration registration = matching(key);
        if (registration != null && inFlight.remove(key.entityId(), registration)) {
            finishDiscard(registration);
        }
    }

    @Override
    public void noPath(RequestKey key) {
        Registration registration = matching(key);
        if (registration != null && inFlight.remove(key.entityId(), registration)) {
            finishDiscard(registration);
        }
    }

    @Override
    public void failed(RequestKey key, Throwable failure) {
        Registration registration = matching(key);
        if (registration != null && inFlight.remove(key.entityId(), registration)) {
            finishDiscard(registration);
            failUntilTick.put(key.entityId(), currentTick + FAIL_COOLDOWN_TICKS);
        }
    }

    public int inFlightCount() { return inFlight.size(); }

    /** Forget registrations/cooldowns at a server boundary. Late results cannot match a future key. */
    public void clear() {
        for (Registration registration : inFlight.values().toArray(Registration[]::new)) {
            if (inFlight.remove(registration.key().entityId(), registration)) {
                finishDiscard(registration);
            }
        }
        failUntilTick.clear();
        // Reset the sweep clock too. A new server starts its tick count near zero, so a
        // timestamp left from a long previous run would suppress sweeping until the new
        // server had been up as long as the old one.
        lastCooldownSweepTick = 0L;
    }
}
