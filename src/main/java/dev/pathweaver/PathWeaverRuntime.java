package dev.pathweaver;

import dev.pathweaver.async.EntityInstallSink;
import dev.pathweaver.async.PathWorkerPool;
import dev.pathweaver.async.ResultInstaller;
import dev.pathweaver.config.PathWeaverConfig;
import dev.pathweaver.snapshot.SnapshotProvider;
import net.minecraft.server.MinecraftServer;

/**
 * Holds PathWeaver's live services and drives their per-server / per-tick lifecycle. The interceptor
 * (Feature A) dispatches into {@link #pool()} and {@link #installer()}; the installer is drained and
 * the snapshot cache cleared once per tick on the main thread.
 */
public final class PathWeaverRuntime {
    private static final PathWeaverRuntime INSTANCE = new PathWeaverRuntime();
    public static PathWeaverRuntime get() { return INSTANCE; }

    private final PathWorkerPool pool = new PathWorkerPool();
    private final ResultInstaller installer = new ResultInstaller();
    private final SnapshotProvider snapshots = new SnapshotProvider();
    private final EntityInstallSink entitySink = new EntityInstallSink();
    private volatile boolean running;

    private PathWeaverRuntime() {}

    public PathWorkerPool pool() { return pool; }
    public ResultInstaller installer() { return installer; }
    public SnapshotProvider snapshots() { return snapshots; }
    public EntityInstallSink entitySink() { return entitySink; }
    public boolean isRunning() { return running; }

    public void onServerStarting(MinecraftServer server) {
        PathWeaverConfig c = PathWeaverConfig.get();
        pool.start(c.resolvedPoolThreads(), c.maxInFlight);
        running = true;
        PathWeaver.LOG.info("PathWeaver runtime started: {} worker thread(s), maxInFlight={}.",
            c.resolvedPoolThreads(), c.maxInFlight);
    }

    public void onServerStopping(MinecraftServer server) {
        running = false;
        pool.shutdown();
    }

    /** Main thread, end of each server tick: install ready paths, then evict stale snapshots. */
    public void onEndTick(MinecraftServer server) {
        installer.drain(entitySink);
        snapshots.clearTick(server.getTickCount());
    }
}
