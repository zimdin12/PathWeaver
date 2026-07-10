package dev.pathweaver.async;

import dev.pathweaver.duck.PWNavigation;
import net.minecraft.world.level.pathfinder.Path;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bridges async results back to the live navigation. The interceptor registers the navigation (as a
 * {@link PWNavigation} duck) at dispatch; the installer calls back here on the main thread to install
 * a fresh path, or discard a stale / failed one. Never touched from worker threads.
 */
public class EntityInstallSink implements ResultInstaller.InstallSink {
    private final Map<Integer, PWNavigation> inFlight = new ConcurrentHashMap<>();

    // FIX 4: entities whose last async search FAILED must skip async until this tick, running vanilla
    // sync instead — otherwise a deterministic failure re-dispatches async forever and never resolves.
    private final Map<Integer, Long> failUntilTick = new ConcurrentHashMap<>();
    private static final long FAIL_COOLDOWN_TICKS = 40L; // ~2s: long enough that a stuck mob won't thrash.

    // Set once per tick by the runtime before draining, so failed() can stamp a cooldown deadline.
    private volatile long currentTick;

    public void setTick(long tick) { this.currentTick = tick; }

    /** Called from the interceptor on the main thread at dispatch time. */
    public void register(int entityId, PWNavigation navigation) {
        inFlight.put(entityId, navigation);
    }

    /** True if this mob already has a search in flight (avoid double-dispatch). */
    public boolean isRegistered(int entityId) {
        return inFlight.containsKey(entityId);
    }

    /**
     * FIX 4: the interceptor asks this before dispatching. While an entity is in its post-failure
     * cooldown we return true, forcing that tick's {@code createPath} to run synchronously in vanilla.
     * The window is self-expiring, so a mob that legitimately cannot path does not thrash the pool.
     */
    public boolean shouldForceSync(int entityId, long tick) {
        Long until = failUntilTick.get(entityId);
        if (until == null) return false;
        if (tick >= until) {
            failUntilTick.remove(entityId);
            return false;
        }
        return true;
    }

    @Override
    public boolean isStale(int entityId, long dispatchTick, double x, double y, double z) {
        PWNavigation nav = inFlight.get(entityId);
        return nav == null || nav.pathweaver$stale(x, y, z);
    }

    @Override
    public void install(int entityId, Path path) {
        PWNavigation nav = inFlight.remove(entityId);
        if (nav != null) {
            failUntilTick.remove(entityId); // a success clears any lingering cooldown.
            nav.pathweaver$install(path);
            dev.pathweaver.PathWeaverRuntime.get().markInstalled();
        }
    }

    @Override
    public void discard(int entityId) {
        PWNavigation nav = inFlight.remove(entityId);
        if (nav != null) {
            nav.pathweaver$onPathfindingDone(); // balance the onPathfindingStart fired at dispatch.
            dev.pathweaver.PathWeaverRuntime.get().markDiscarded();
        }
    }

    @Override
    public void failed(int entityId) {
        PWNavigation nav = inFlight.remove(entityId);
        if (nav != null) {
            nav.pathweaver$onPathfindingDone();
            dev.pathweaver.PathWeaverRuntime.get().markDiscarded();
        }
        failUntilTick.put(entityId, currentTick + FAIL_COOLDOWN_TICKS); // FIX 4: force sync next tick(s).
    }

    public int inFlightCount() { return inFlight.size(); }

    /** FIX 5: forget everything from a previous server session on start/stop. */
    public void clear() {
        inFlight.clear();
        failUntilTick.clear();
    }
}
