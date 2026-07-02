package dev.pathweaver.async;

import dev.pathweaver.duck.PWNavigation;
import net.minecraft.world.level.pathfinder.Path;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bridges async results back to the live navigation. The interceptor registers the navigation (as a
 * {@link PWNavigation} duck) at dispatch; the installer calls back here on the main thread to install
 * a fresh path or discard a stale/failed one. Never touched from worker threads.
 */
public class EntityInstallSink implements ResultInstaller.InstallSink {
    private final Map<Integer, PWNavigation> inFlight = new ConcurrentHashMap<>();

    /** Called from the interceptor on the main thread at dispatch time. */
    public void register(int entityId, PWNavigation navigation) {
        inFlight.put(entityId, navigation);
    }

    @Override
    public boolean isStale(int entityId, long dispatchTick, double x, double y, double z) {
        PWNavigation nav = inFlight.get(entityId);
        return nav == null || nav.pathweaver$stale(x, y, z);
    }

    @Override
    public void install(int entityId, Path path) {
        PWNavigation nav = inFlight.remove(entityId);
        if (nav != null) nav.pathweaver$install(path);
    }

    @Override
    public void discard(int entityId) {
        inFlight.remove(entityId);
    }

    public int inFlightCount() { return inFlight.size(); }
}
