package dev.pathweaver.async;

import dev.pathweaver.PathWeaver;
import dev.pathweaver.config.PathWeaverConfig;
import dev.pathweaver.duck.PWNavigation;
import net.minecraft.world.level.pathfinder.Path;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Main-thread bridge from request-keyed async completions to live navigation. Installation requires
 * the exact request key plus unchanged entity UUID, world/dimension, navigation, path and target intent.
 */
public class EntityInstallSink implements ResultInstaller.InstallSink {
    public enum PendingDecision { NONE, PRESERVE, SUPERSEDE }

    private record Registration(RequestKey key, PWNavigation navigation,
                                NavigationIdentity identity, RequestTarget target) { }

    private static final RequestTarget UNSPECIFIED_TARGET =
        RequestTarget.of(Set.of(), 0, false, 0, 0.0F);
    private final Map<Integer, Registration> inFlight = new ConcurrentHashMap<>();
    private final Map<Integer, Long> failUntilTick = new ConcurrentHashMap<>();
    private final AtomicBoolean callbackFailureLogged = new AtomicBoolean();
    private final AtomicBoolean installFailureLogged = new AtomicBoolean();
    private static final long FAIL_COOLDOWN_TICKS = 40L;
    private volatile long currentTick;

    public void setTick(long tick) { this.currentTick = tick; }

    /** Called from the interceptor on the main thread at dispatch time. */
    public void register(RequestKey key, PWNavigation navigation, RequestTarget target) {
        Registration next = new Registration(
            key, navigation, navigation.pathweaver$identity(), target);
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
        finishCallback(registration);
        dev.pathweaver.PathWeaverRuntime.get().markDiscarded();
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

    public boolean shouldForceSync(int entityId, long tick) {
        Long until = failUntilTick.get(entityId);
        if (until == null) return false;
        if (tick >= until) {
            failUntilTick.remove(entityId);
            return false;
        }
        return true;
    }

    private Registration matching(RequestKey key) {
        Registration registration = inFlight.get(key.entityId());
        return registration != null && registration.key().equals(key) ? registration : null;
    }

    @Override
    public boolean isStale(RequestKey key, long dispatchTick, double x, double y, double z) {
        Registration registration = matching(key);
        if (registration == null) return true;
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
    }
}
