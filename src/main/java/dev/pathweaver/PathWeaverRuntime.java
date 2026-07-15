package dev.pathweaver;

import dev.pathweaver.async.EntityInstallSink;
import dev.pathweaver.async.PathWorkerPool;
import dev.pathweaver.async.RequestKey;
import dev.pathweaver.async.ResultInstaller;
import dev.pathweaver.config.PathWeaverConfig;
import net.minecraft.server.MinecraftServer;

/**
 * Holds PathWeaver's live services and drives their per-server / per-tick lifecycle. The interceptor
 * (Feature A) dispatches into {@link #pool()} and {@link #installer()}; the installer is drained once
 * per tick on the main thread. Start/stop advance a server epoch, clear tracked registrations/results,
 * and replace the capacity-isolated worker generation so late prior-session completions cannot mutate
 * the current session.
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
    private final java.util.concurrent.atomic.AtomicLong serverEpoch = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong requestTokens = new java.util.concurrent.atomic.AtomicLong();

    public void markDispatched() { dispatched.incrementAndGet(); }
    public void markInstalled() { installed.incrementAndGet(); }
    public void markDiscarded() { discarded.incrementAndGet(); }

    private PathWeaverRuntime() {}

    public PathWorkerPool pool() { return pool; }
    public ResultInstaller installer() { return installer; }
    public EntityInstallSink entitySink() { return entitySink; }
    public boolean isRunning() { return running; }
    public long currentServerEpoch() { return serverEpoch.get(); }

    /** Main-thread dispatch identity; every accepted attempt gets a process-unique token. */
    public RequestKey nextRequestKey(int entityId) {
        if (!running) throw new IllegalStateException("PathWeaver runtime is not running");
        return new RequestKey(serverEpoch.get(), requestTokens.incrementAndGet(), entityId);
    }

    public void onServerStarting(MinecraftServer server) {
        running = false;
        long epoch = serverEpoch.incrementAndGet();
        PathWeaverConfig c = PathWeaverConfig.get();
        entitySink.clear();
        installer.clear();
        pool.start(c.resolvedPoolThreads(), c.maxInFlight);
        dispatched.set(0);
        installed.set(0);
        discarded.set(0);
        running = true;
        PathWeaver.LOG.info("PathWeaver runtime started: epoch={}, {} worker thread(s), maxInFlight={}.",
            epoch, c.resolvedPoolThreads(), c.maxInFlight);
    }

    public void onServerStopping(MinecraftServer server) {
        running = false;
        serverEpoch.incrementAndGet(); // invalidate every key before interrupting workers
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
