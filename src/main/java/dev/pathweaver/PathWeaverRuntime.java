package dev.pathweaver;

import dev.pathweaver.async.EntityInstallSink;
import dev.pathweaver.async.PathWorkerPool;
import dev.pathweaver.async.RequestKey;
import dev.pathweaver.async.ResultInstaller;
import dev.pathweaver.config.PathWeaverConfig;
import dev.pathweaver.gate.MobOriginGate;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.zombie.Zombie;

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
        resetWasteReportingForTests();
        running = true;
        PathWeaver.LOG.info("PathWeaver runtime started: epoch={}, {} worker thread(s), maxInFlight={}.",
            epoch, c.resolvedPoolThreads(), c.maxInFlight);
        warnAboutSelfDefeatingSettings(c);
        PathWeaver.LOG.info("Mob-origin CodeSource probe: Mob={}, Zombie={}, moddedBypass={}.",
            MobOriginGate.isAllowed(Mob.class, false), MobOriginGate.isAllowed(Zombie.class, false),
            c.moddedMobAsyncAllowed());
        if (c.enabled) {
            PathWeaver.LOG.warn("Experimental async boundary: mod-defined mobs are {} by the origin gate; "
                    + "vanilla-class Mixins and live mob/world/block reads remain unsnapshotted.",
                c.moddedMobAsyncAllowed()
                    ? (c.allowModdedMobAsync
                        ? "explicitly allowed (unsafe override)"
                        : "allowed by compatibilityTier=ALL (unsafe)")
                    : "synchronous");
        }
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

    /**
     * Below this, a result is rejected because its mob moved at all, so nothing is ever installed.
     * A mob under way covers more than a block while a search runs.
     */
    static final double MIN_USEFUL_STALENESS_BLOCKS = 1.0;
    /** Above this, measurement showed most finished searches arriving too late to be wanted. */
    static final int MAX_USEFUL_IN_FLIGHT = 256;

    /**
     * Warn when a setting is configured to a value measured to defeat the mod.
     *
     * <p>Both of these are legal numbers that produce no error and no TPS drop, so nothing else
     * surfaces them: the pool keeps working and the results keep being thrown away. They are reported
     * rather than clamped, because a config that silently does something other than what it says has
     * caused more trouble in this project than a config that does what you asked and tells you it is
     * a bad idea.
     */
    static void warnAboutSelfDefeatingSettings(PathWeaverConfig c) {
        if (c.stalenessMoveThreshold < MIN_USEFUL_STALENESS_BLOCKS) {
            PathWeaver.LOG.warn("stalenessMoveThreshold={} discards a finished search when its mob has "
                    + "moved that far, and a moving mob covers more than a block while one runs. "
                    + "Measured at 0, {}% of finished searches were unusable. The value is being "
                    + "honoured as configured; raise it to about {} to get any benefit.",
                c.stalenessMoveThreshold, 91.8, MIN_USEFUL_STALENESS_BLOCKS * 4);
        }
        if (c.maxInFlight > MAX_USEFUL_IN_FLIGHT) {
            PathWeaver.LOG.warn("maxInFlight={} is above the measured useful range. It is an admission "
                    + "bound rather than a buffer: a deeper queue makes each result land later, and a "
                    + "result that arrives after its mob has asked again is superseded. Measured on a "
                    + "371-mod pack, {} left 90.7% of finished searches unused and {} left effectively "
                    + "all of them. The value is being honoured as configured; {} is the shipped "
                    + "default.", c.maxInFlight, 1024, 4096, MAX_USEFUL_IN_FLIGHT);
        }
    }

    /** Main thread, end of each server tick: stamp the tick then install ready paths. */
    public void onEndTick(MinecraftServer server) {
        entitySink.setTick(server.getTickCount());
        installer.drain(entitySink);
        reportIfMostResultsAreWasted(server.getTickCount());
    }

    /** Ticks between install-ratio samples. One minute: long enough that a burst cannot trip it. */
    static final int WASTE_SAMPLE_INTERVAL_TICKS = 1200;
    /** Below this install ratio the pool is doing work nothing consumes. */
    static final double WASTE_RATIO_THRESHOLD = 0.25;
    /** Ignore quiet windows; a handful of superseded searches says nothing about the configuration. */
    static final long WASTE_MIN_SAMPLE = 500L;

    /**
     * Consecutive bad windows required before warning.
     *
     * <p>Two, not one: dispatches and their installs can straddle a sampling boundary, so a single
     * large burst just before a sample lands its work in the next window and makes this one look
     * almost entirely wasted. A configuration that is actually wrong stays wrong for the next minute
     * too.
     */
    static final int WASTE_CONSECUTIVE_WINDOWS = 2;

    private int consecutiveWastedWindows;
    private long lastWasteCheckTick;
    private long lastWasteDispatched;
    private long lastWasteInstalled;
    private boolean wasteReported;

    /**
     * Warn once when almost no completed search is being used.
     *
     * <p>This is a configuration footgun rather than a failure, which is why nothing else catches it:
     * {@code maxInFlight} accepts up to {@value dev.pathweaver.config.PathWeaverConfig#MAX_IN_FLIGHT},
     * and setting it high feels like it should help. It does the opposite. Workers are a fixed pool, so
     * a deeper allowance only lengthens the queue, and a result that arrives after the mob has already
     * asked again is superseded. Measured on a 371-mod pack with 1024 mobs repathing every 6 ticks,
     * as a share of dispatches not installed within the capture window: 13.5% at 256, 90.7% at 1024,
     * and nothing at all installed while observing at 4096 -- with no errors logged and the server
     * still reporting 20 TPS, so the mod looks like it is working while achieving nothing.
     *
     * <p>A heuristic, not a diagnosis: it samples counters over a window and does not follow
     * individual requests, so a dispatch counted here may be installed in the next window.
     */
    void reportIfMostResultsAreWasted(long tick) {
        if (wasteReported) return;
        if (tick - lastWasteCheckTick < WASTE_SAMPLE_INTERVAL_TICKS) return;
        lastWasteCheckTick = tick;
        long dispatchedNow = dispatched.get();
        long installedNow = installed.get();
        long windowDispatched = dispatchedNow - lastWasteDispatched;
        long windowInstalled = installedNow - lastWasteInstalled;
        lastWasteDispatched = dispatchedNow;
        lastWasteInstalled = installedNow;
        // Any window that is not itself bad breaks the run, including one too quiet to judge.
        // Otherwise a burst, a quiet window that absorbs its late installs, and an unrelated burst
        // much later would count as two consecutive bad windows and warn on windows that were not
        // consecutive at all.
        boolean windowIsBad = windowDispatched >= WASTE_MIN_SAMPLE
            && windowInstalled < windowDispatched * WASTE_RATIO_THRESHOLD;
        if (!windowIsBad) {
            consecutiveWastedWindows = 0;
            return;
        }
        if (++consecutiveWastedWindows < WASTE_CONSECUTIVE_WINDOWS) return;
        wasteReported = true;
        // Deliberately phrased as a likely cause rather than a diagnosis: this samples counters, it
        // does not attribute individual results, so it is a heuristic.
        PathWeaver.LOG.warn("Only {} of {} async path searches were installed in the last {} ticks, "
                + "for {} consecutive sampling windows. Results are most likely completing after the "
                + "mob has already asked again, which wastes the work. maxInFlight={} may be too high "
                + "for {} worker thread(s): a deeper queue adds latency rather than throughput. "
                + "Consider lowering maxInFlight (256 is the shipped default) or raising poolThreads.",
            windowInstalled, windowDispatched, WASTE_SAMPLE_INTERVAL_TICKS,
            consecutiveWastedWindows, PathWeaverConfig.get().maxInFlight,
            PathWeaverConfig.get().resolvedPoolThreads());
    }

    /**
     * Re-arm the warning and start a fresh sampling window from the counters as they stand.
     *
     * <p>Snapshotting rather than zeroing matters: the counters are on a process singleton, so a
     * window that assumed it started at zero would attribute everything already counted to itself.
     */
    void resetWasteReportingForTests() {
        lastWasteCheckTick = 0L;
        consecutiveWastedWindows = 0;
        lastWasteDispatched = dispatched.get();
        lastWasteInstalled = installed.get();
        wasteReported = false;
    }

    /** Test seam. */
    boolean wasteReported() { return wasteReported; }
}
