package dev.pathweaver.async;

import dev.pathweaver.duck.PWNavigation;
import net.minecraft.world.level.pathfinder.Path;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Main-thread bridge from request-keyed async completions to live navigation. A completion may mutate
 * navigation state only when its full server-epoch/request-token/entity identity still matches the
 * registered request; numeric entity ID reuse alone is never authoritative.
 */
public class EntityInstallSink implements ResultInstaller.InstallSink {
    private record Registration(RequestKey key, PWNavigation navigation) { }

    private final Map<Integer, Registration> inFlight = new ConcurrentHashMap<>();
    private final Map<Integer, Long> failUntilTick = new ConcurrentHashMap<>();
    private static final long FAIL_COOLDOWN_TICKS = 40L;
    private volatile long currentTick;

    public void setTick(long tick) { this.currentTick = tick; }

    /** Called from the interceptor on the main thread at dispatch time. */
    public void register(RequestKey key, PWNavigation navigation) {
        inFlight.put(key.entityId(), new Registration(key, navigation));
    }

    public boolean isRegistered(int entityId) {
        return inFlight.containsKey(entityId);
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
        return registration == null || registration.navigation().pathweaver$stale(x, y, z);
    }

    @Override
    public void install(RequestKey key, Path path) {
        Registration registration = matching(key);
        if (registration != null && inFlight.remove(key.entityId(), registration)) {
            failUntilTick.remove(key.entityId());
            registration.navigation().pathweaver$install(path);
            dev.pathweaver.PathWeaverRuntime.get().markInstalled();
        }
    }

    @Override
    public void discard(RequestKey key) {
        Registration registration = matching(key);
        if (registration != null && inFlight.remove(key.entityId(), registration)) {
            registration.navigation().pathweaver$onPathfindingDone();
            dev.pathweaver.PathWeaverRuntime.get().markDiscarded();
        }
    }

    @Override
    public void failed(RequestKey key) {
        Registration registration = matching(key);
        if (registration != null && inFlight.remove(key.entityId(), registration)) {
            registration.navigation().pathweaver$onPathfindingDone();
            dev.pathweaver.PathWeaverRuntime.get().markDiscarded();
            failUntilTick.put(key.entityId(), currentTick + FAIL_COOLDOWN_TICKS);
        }
    }

    public int inFlightCount() { return inFlight.size(); }

    /** Forget registrations/cooldowns at a server boundary. Late results cannot match a future key. */
    public void clear() {
        inFlight.clear();
        failUntilTick.clear();
    }
}
