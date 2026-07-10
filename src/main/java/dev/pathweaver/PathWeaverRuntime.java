package dev.pathweaver;

import dev.pathweaver.async.EntityInstallSink;
import dev.pathweaver.async.PathWorkerPool;
import dev.pathweaver.async.ResultInstaller;
import dev.pathweaver.config.PathWeaverConfig;
import net.minecraft.server.MinecraftServer;

/**
 * Holds PathWeaver's live services and drives their per-server / per-tick lifecycle. The interceptor
 * (Feature A) dispatches into {@link #pool()} and {@link #installer()}; the installer is drained once
 * per tick on the main thread. start/stop are idempotent and reset all in-flight state (FIX 5), so
 * async never silently ratchets off across world reloads.
 */
public final class PathWeaverRuntime {
    private static final PathWeaverRuntime INSTANCE = new PathWeaverRuntime();
    public static PathWeaverRuntime get() { return INSTANCE; }

    private final PathWorkerPool pool = new PathWorkerPool();
    private final ResultInstaller installer = new ResultInstaller();
    private final EntityInstallSink entitySink = new EntityInstallSink();
    private volatile boolean running;

    private final java.util.concurrent.atomic.AtomicLong dispatched = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong installed = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong discarded = new java.util.concurrent.atomic.AtomicLong();

    public void markDispatched() { dispatched.incrementAndGet(); }
    public void markInstalled() { installed.incrementAndGet(); }
    public void markDiscarded() { discarded.incrementAndGet(); }

    private PathWeaverRuntime() {}

    public PathWorkerPool pool() { return pool; }
    public ResultInstaller installer() { return installer; }
    public EntityInstallSink entitySink() { return entitySink; }
    public boolean isRunning() { return running; }

    public void onServerStarting(MinecraftServer server) {
        PathWeaverConfig c = PathWeaverConfig.get();
        // Reset all cross-session state BEFORE arming, so a prior reload can't leave async wedged.
        entitySink.clear();
        installer.clear();
        pool.start(c.resolvedPoolThreads(), c.maxInFlight); // start() itself resets inFlight to 0.
        dispatched.set(0);
        installed.set(0);
        discarded.set(0);
        running = true;
        PathWeaver.LOG.info("PathWeaver runtime started: {} worker thread(s), maxInFlight={}.",
            c.resolvedPoolThreads(), c.maxInFlight);
    }

    public void onServerStopping(MinecraftServer server) {
        running = false;
        pool.shutdown();
        entitySink.clear();
        installer.clear();
        PathWeaver.LOG.info("PathWeaver stats: dispatched={}, installed={}, discarded={} (async pathfinding).",
            dispatched.get(), installed.get(), discarded.get());
    }

    /** Main thread, end of each server tick: stamp the tick then install ready paths. */
    public void onEndTick(MinecraftServer server) {
        entitySink.setTick(server.getTickCount());
        installer.drain(entitySink);
    }
}
