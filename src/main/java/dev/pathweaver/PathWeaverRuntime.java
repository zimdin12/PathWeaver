package dev.pathweaver;

import dev.pathweaver.async.EntityInstallSink;
import java.util.List;
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
    private final java.util.concurrent.atomic.AtomicLong serverEpoch = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong requestTokens = new java.util.concurrent.atomic.AtomicLong();

    /**
     * One counter per way a request can end, rather than one bucket labelled "discarded".
     *
     * <p>Indexed by ordinal so recording an outcome is a single atomic increment on the main thread's
     * completion path, with no map lookup and no allocation.
     */
    private final java.util.concurrent.atomic.AtomicLongArray outcomes =
        new java.util.concurrent.atomic.AtomicLongArray(
            dev.pathweaver.async.RequestOutcome.values().length);

    /** Read-only counter access for the in-game diagnostic. */
    public long dispatchedCount() { return dispatched.get(); }

    public long outcomeCount(dev.pathweaver.async.RequestOutcome outcome) {
        return outcomes.get(outcome.ordinal());
    }

    public long installedCount() {
        return outcomeCount(dev.pathweaver.async.RequestOutcome.INSTALLED);
    }

    /**
     * Every outcome that produced nothing usable.
     *
     * <p>Deliberately excludes searches that proved no route exists. Counting those here is what made
     * the old single number unreadable, and it is the number the mod page quotes.
     */
    public long discardedCount() {
        long total = 0L;
        for (dev.pathweaver.async.RequestOutcome outcome : dev.pathweaver.async.RequestOutcome.values()) {
            if (outcome.isDiscard()) total += outcomeCount(outcome);
        }
        return total;
    }

    /**
     * Every non-zero outcome, so the shutdown line explains its own totals.
     *
     * <p>"discarded=41028" invites exactly one wrong conclusion, that 41028 searches were wasted.
     * Naming the causes shows how many of them were mobs arriving and stopping, which is the mod
     * working rather than failing.
     */
    private String outcomeBreakdown() {
        StringBuilder detail = new StringBuilder();
        for (dev.pathweaver.async.RequestOutcome outcome : dev.pathweaver.async.RequestOutcome.values()) {
            long count = outcomeCount(outcome);
            if (count == 0L || outcome == dev.pathweaver.async.RequestOutcome.INSTALLED) continue;
            detail.append(detail.isEmpty() ? " [" : ", ")
                .append(outcome.description()).append('=').append(count);
        }
        return detail.isEmpty() ? "" : detail.append(']').toString();
    }

    public void markDispatched() { dispatched.incrementAndGet(); }

    /**
     * One setup-failure warning per SERVER SESSION, matching what the message promises.
     *
     * <p>It lived on the mixin as a static, so it burned once per JVM while the counter it points at
     * is cleared by {@link #onServerStarting}. On a client, loading a second world reset the counter
     * and kept the flag: {@code /pathweaver status} then showed a climbing "dispatch setup failed"
     * with no log line anywhere, and the line printed in the first world had explicitly promised the
     * counter would keep counting.
     */
    public boolean claimSetupFailureLog() {
        return setupFailureLogged.compareAndSet(false, true);
    }

    private final java.util.concurrent.atomic.AtomicBoolean setupFailureLogged =
        new java.util.concurrent.atomic.AtomicBoolean();

    public void markOutcome(dev.pathweaver.async.RequestOutcome outcome) {
        outcomes.incrementAndGet(outcome.ordinal());
    }

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
        // clear(false): do not run epilogues here. Startup normally follows a stop that already
        // drained them, so this map is empty -- but pool.start() replaces a generation with
        // shutdownNow(), which does not wait, so on any path that skipped a clean stop a worker
        // could still be running. Asserting quiescence we have not established is exactly the
        // mistake that put an NPE inside a worker at server stop.
        // Zero the counters BEFORE clearing, not after. clear() records a SERVER_RESET for every
        // leftover registration it finds -- which only happens on the abnormal path this call exists
        // to cover, a start with no preceding stop -- and wiping the array afterwards destroyed the
        // one piece of evidence that leftovers existed.
        dispatched.set(0);
        for (int i = 0; i < outcomes.length(); i++) outcomes.set(i, 0L);
        // Re-arm alongside the counter it refers to. Leaving it burnt across a world switch left the
        // next session accumulating "dispatch setup failed" with nothing in the log to explain it.
        setupFailureLogged.set(false);
        entitySink.clear(false);
        installer.clear();
        pool.start(c.resolvedPoolThreads(), c.maxInFlight);
        resetWasteReportingForTests();
        long leftovers = outcomeCount(dev.pathweaver.async.RequestOutcome.SERVER_RESET);
        if (leftovers > 0L) {
            PathWeaver.LOG.warn("{} request(s) were still registered from a previous session and have "
                + "been discarded. A clean stop drains these, so this means the last one was not "
                + "clean.", leftovers);
        }
        running = true;
        PathWeaver.LOG.info("PathWeaver runtime started: epoch={}, {} worker thread(s), maxInFlight={}.",
            epoch, pool.threads(), pool.maxInFlight());
        warnAboutSelfDefeatingSettings(c);
        if (c.enabled) {
            List<String> coreAdvice =
                lowCoreAdvice(Runtime.getRuntime().availableProcessors(), pool.threads());
            if (!coreAdvice.isEmpty()) {
                PathWeaver.LOG.warn("======================== PathWeaver ========================");
                for (String line : coreAdvice) PathWeaver.LOG.warn(line);
                PathWeaver.LOG.warn("============================================================");
            }
        }
        reportWhetherItIsDoingAnything(c);
        PathWeaver.LOG.info("Mob-origin CodeSource probe: Mob={}, Zombie={}, moddedBypass={}.",
            MobOriginGate.isAllowed(Mob.class, false), MobOriginGate.isAllowed(Zombie.class, false),
            c.moddedMobAsyncAllowed());
        if (c.enabled) {
            PathWeaver.LOG.warn("Experimental async boundary: mod-defined mobs are {} by the origin gate; "
                    + "vanilla-class Mixins and live mob/world/block reads remain unsnapshotted.",
                c.moddedMobAsyncAllowed()
                    ? (c.allowModdedMobAsync
                        ? "explicitly allowed (unsafe override)"
                        : "allowed by compatibilityTier=UNSAFE")
                    : "synchronous");
        }
    }


    /**
     * Say when the unsafe tier has waived a <em>correctness</em> gate, not just a thread-safety one.
     *
     * <p>The land-path-type registry latch stops Walk dispatching while a mod has registered a
     * dynamic provider that is not certified — a rule saying "mobs should avoid my block". A worker
     * cannot read the live provider map, so it answers "no rule exists", and the mob is routed over
     * a block the mod marked dangerous. The same mob's synchronous fallback consults the real
     * registry and avoids it, so the behaviour differs between two code paths for one mob depending
     * on worker-pool load.
     *
     * <p>The tier waives this latch on the argument that "ignore every check" should mean what it
     * says. That was a defensible reading of an explicit opt-in. It is a weaker one now that
     * {@code UNSAFE} is the shipped default, because it makes divergent routing the out-of-the-box
     * behaviour on any pack carrying an uncertified provider. Certification already covers the
     * providers that have been audited (Farmer's Delight's stove, and static providers generically),
     * so this fires only for genuinely unknown ones — but when it fires it is a gameplay bug, not a
     * performance trade, and it should not be discovered by watching a mob walk into a fire.
     */
    private void warnIfLandProviderCorrectnessIsWaived(PathWeaverConfig c) {
        if (!c.bypassesCompatibilityScan()) return;
        if (dev.pathweaver.gate.FabricLandPathRegistryLatch.allowsWalkDispatch()) return;
        PathWeaver.LOG.warn("");
        PathWeaver.LOG.warn("A mod has registered an uncertified land path-type rule (\"mobs should");
        PathWeaver.LOG.warn("avoid this block\"). Worker threads cannot read that rule, so off-thread");
        PathWeaver.LOG.warn("searches will treat those blocks as ordinary ground while synchronous");
        PathWeaver.LOG.warn("ones still avoid them. Expect mobs to occasionally route over a block a");
        PathWeaver.LOG.warn("mod marked dangerous. This is a correctness gate, not a thread-safety");
        PathWeaver.LOG.warn("one, and compatibilityTier=UNSAFE waives it along with everything else.");
        PathWeaver.LOG.warn("Set compatibilityTier=AUDITED if that matters more than the throughput.");
    }

    /**
     * Say, at world start, whether this mod is going to do anything at all — and if not, what to do.
     *
     * <p>The scan already logs a line per offending mod, but those appear during early mixin
     * scanning, hundreds of lines before a world loads, in a format that reads like a warning about
     * the other mod rather than a statement that PathWeaver is switched off. The result was a mod
     * that installs, does nothing, and never says so. On a heavily-modded pack that is the normal
     * outcome, not an edge case, and it is the single most common thing an operator needs to know.
     *
     * <p>Deliberately at {@code WARN} when inert. It is not an error — failing closed is the design
     * — but "you installed something that is doing nothing" is worth interrupting for.
     */
    private void reportWhetherItIsDoingAnything(PathWeaverConfig c) {
        if (!c.enabled) {
            PathWeaver.LOG.warn("PathWeaver is disabled in the config; pathfinding is fully vanilla.");
            return;
        }
        java.util.List<String> blockers = dev.pathweaver.gate.ForeignMixinScanner.blockingModIds();
        // Ask the gate per family rather than counting the denial set.
        //
        // The denial set holds CLASSES, not families, and SafetyGate.isDenied matches with
        // isAssignableFrom -- so denying WalkNodeEvaluator alone actually blocks five families while
        // the set size says one. The old code printed that size as "All {} movement families", which
        // meant a pack denying only Swim announced "PathWeaver is doing NOTHING on this pack. All 1
        // movement families are running on the server thread" while five families were dispatching
        // and installing paths. Wrong in both directions, in the block most likely to be pasted into
        // a bug report, from the method whose whole purpose is to stop this mod lying about itself.
        int total = dev.pathweaver.gate.SafetyGate.allowlisted().size();
        int eligible = 0;
        for (Class<?> family : dev.pathweaver.gate.SafetyGate.allowlisted()) {
            // canDispatch, not isAllowed: the latter omits the land-registry latch that dispatch
            // also consults, so this block used to announce all six families active on a pack where
            // five of them were being refused every tick.
            if (dev.pathweaver.gate.SafetyGate.canDispatch(family)) eligible++;
        }
        int denied = total - eligible;

        java.util.List<String> trusted = dev.pathweaver.gate.ForeignMixinScanner.trustedModIdsInUse();
        if (denied == 0) {
            PathWeaver.LOG.info("PathWeaver is ACTIVE: all {} movement families can path off-thread"
                + "{}.", eligible, blockers.isEmpty() ? "" : " (waived: " + blockers.size() + " mod(s))");
            if (!trusted.isEmpty()) {
                PathWeaver.LOG.warn("{} mod(s) are running unaudited because you listed them as "
                    + "trusted: {}", trusted.size(), String.join(", ", trusted));
            }
            warnIfLandProviderCorrectnessIsWaived(c);
            return;
        }
        PathWeaver.LOG.warn("======================== PathWeaver ========================");
        if (eligible == 0) {
            PathWeaver.LOG.warn("PathWeaver is doing NOTHING on this pack. All {} movement", total);
            PathWeaver.LOG.warn("families are running on the server thread, exactly as vanilla.");
        } else {
            PathWeaver.LOG.warn("PathWeaver is PARTLY active: {} of {} movement families can path",
                eligible, total);
            PathWeaver.LOG.warn("off-thread; the other {} run on the server thread as vanilla.", denied);
        }
        if (!blockers.isEmpty()) {
            PathWeaver.LOG.warn("");
            PathWeaver.LOG.warn("{} mod(s) modify pathfinding code and have not been audited:",
                blockers.size());
            PathWeaver.LOG.warn("  {}", String.join(", ", blockers));
        }
        // Blame the tier only when the tier is what refused. `isAllowed` is
        // `allowlisted && !denied && canClone`, so a family that failed ONLY the clone check -- a JVM
        // refusing setAccessible, a NoClassDefFoundError swallowed while resolving -- used to get the
        // full "you opted into AUDITED" lecture plus advice to edit trustedMods, none of which would
        // change anything. Fixing this method to stop lying about COUNTS gave it a new way to lie
        // about CAUSE.
        // Derive the cause; do not infer it from "no mod was blamed".
        //
        // The first version of this branch assumed an empty blocker list meant a clone failure and
        // told the operator that changing compatibilityTier would NOT help. A live A/B proved that
        // wrong: with a mod registering an uncertified land path-type rule and its mixins trusted,
        // `blockers` is empty, five of six families are refused by the LAND-REGISTRY LATCH, and
        // compatibilityTier=UNSAFE waives it completely. The banner was sending operators to file a
        // bug report about a fully explained, self-inflicted, documented state -- while
        // /pathweaver status and /pathweaver mobs each named a different cause again. Three
        // diagnostics, three answers, in the release whose whole thesis is that they agree.
        boolean latchRefused = dev.pathweaver.gate.SafetyGate.landRegistryBlocksWalkFamilies();
        // A failed scan outranks everything below, and this branch did not know it existed.
        // On that path the scanner denies every family, names no mod (it could not read the configs
        // that would have blamed one), and leaves hooksVerified false -- so the FIRST arm below
        // matched and told the operator "compatibilityTier=UNSAFE waives this". It does not: the
        // tier waives what the scan found, not the scan being unable to look, and the scanner block
        // forty lines earlier in this same log already says so in capitals.
        boolean scanFailed =
            dev.pathweaver.gate.ForeignMixinScanner.lastScanReport().decision().failed() > 0;
        if (scanFailed) {
            PathWeaver.LOG.warn("");
            PathWeaver.LOG.warn("The compatibility scan could not complete, so nothing can be");
            PathWeaver.LOG.warn("waived: compatibilityTier does NOT help here, because the tier");
            PathWeaver.LOG.warn("waives what the scan found, not the scan being unable to look.");
            PathWeaver.LOG.warn("The scanner's own message above has the details.");
        } else if (blockers.isEmpty() && latchRefused) {
            PathWeaver.LOG.warn("");
            PathWeaver.LOG.warn("No mod was blamed by the compatibility scan. These families are");
            PathWeaver.LOG.warn("held back by Fabric's land path-type registry instead: either a mod");
            PathWeaver.LOG.warn("registered an uncertified land rule, or the registry hooks could not");
            PathWeaver.LOG.warn("be verified against this Fabric API build.");
            PathWeaver.LOG.warn("");
            PathWeaver.LOG.warn("Setting compatibilityTier=UNSAFE waives this (restart required).");
            PathWeaver.LOG.warn("trustedMods does NOT -- it waives the mixin scan, not this.");
        } else if (blockers.isEmpty()) {
            PathWeaver.LOG.warn("");
            PathWeaver.LOG.warn("No mod was blamed for this. The families above were refused for a");
            PathWeaver.LOG.warn("reason other than the compatibility scan -- most likely their");
            PathWeaver.LOG.warn("evaluator could not be cloned on this JVM. Changing compatibilityTier");
            PathWeaver.LOG.warn("or trustedMods will NOT help. Please report it with your log.");
        } else {
            PathWeaver.LOG.warn("");
            PathWeaver.LOG.warn("This is what compatibilityTier=AUDITED gives you: unverified code");
            PathWeaver.LOG.warn("is not run on worker threads. It is NOT the shipped default -- you");
            PathWeaver.LOG.warn("opted into it. On a heavily-modded pack it usually means no benefit.");
            PathWeaver.LOG.warn("");
            PathWeaver.LOG.warn("Two ways to run anyway, both unsafe, both needing a restart:");
            PathWeaver.LOG.warn("  - add some of the mods above to trustedMods, which accepts only");
            PathWeaver.LOG.warn("    those and leaves the scan armed for anything you install later;");
            PathWeaver.LOG.warn("  - or set compatibilityTier=UNSAFE, which waives every check there");
            PathWeaver.LOG.warn("    is, now and in future. Back up your world either way.");
            if (!trusted.isEmpty()) {
                PathWeaver.LOG.warn("Already trusted: {}", String.join(", ", trusted));
            }
        }
        PathWeaver.LOG.warn("Run /pathweaver status in game for the same answer at any time.");
        PathWeaver.LOG.warn("============================================================");
    }

    public void onServerStopping(MinecraftServer server) {
        running = false;
        serverEpoch.incrementAndGet(); // invalidate every key before interrupting workers
        boolean workersQuiesced = pool.shutdown();
        entitySink.clear(workersQuiesced);
        installer.clear();
        PathWeaver.LOG.info("PathWeaver stats: dispatched={}, installed={}, discarded={}{}.",
            dispatched.get(), installedCount(), discardedCount(), outcomeBreakdown());
    }

    /**
     * Below this, a result is rejected because its mob moved at all, so nothing is ever installed.
     * A mob under way covers more than a block while a search runs.
     */
    static final double MIN_USEFUL_STALENESS_BLOCKS = 1.0;
    /** Above this, measurement showed most finished searches arriving too late to be wanted. */
    static final int MAX_USEFUL_IN_FLIGHT = 256;

    /** At or below this, there is no core for a worker to use that the game does not already want. */
    static final int CORES_WITH_NO_HEADROOM = 2;
    /** At or below this, one worker is all the auto-sizer produces and the win is marginal. */
    static final int CORES_WITH_LITTLE_HEADROOM = 4;

    /**
     * Recommend turning the mod off on a machine with too few cores to benefit from it.
     *
     * <p>This mod does not make pathfinding cheaper. It moves the same A* work onto another thread so
     * the server thread has more headroom, and it adds a little work of its own on the way — the
     * prologue, the epilogue and the install all run on the main thread, and every discarded search is
     * CPU spent for nothing. That trade only pays when there is a core free for the worker to use.
     * On two cores there is not: the worker competes with the server thread for the same silicon, so
     * the A* is not removed from the critical path, it is handed sideways with extra bookkeeping.
     *
     * <p>Stated as a recommendation rather than enforced, and the mod is not switched off
     * automatically — the same reason the self-defeating settings above are reported rather than
     * clamped. It is also honest about its own basis: this is a structural argument from how the work
     * is scheduled, not a measurement taken on a two-core machine. Both benchmarks behind this
     * project's published numbers ran on many-core hardware.
     *
     * @return the lines to log, empty when the machine has enough headroom to be worth it
     */
    static List<String> lowCoreAdvice(int availableProcessors, int workers) {
        if (availableProcessors > CORES_WITH_LITTLE_HEADROOM) return List.of();
        if (availableProcessors <= CORES_WITH_NO_HEADROOM) {
            return List.of(
                "This machine reports " + availableProcessors + " processor(s), and PathWeaver is "
                    + "running " + workers + " worker thread(s) on it.",
                "PathWeaver does not make pathfinding cheaper. It moves the work to another thread "
                    + "so the server thread has room.",
                "With this few cores there is no free core for a worker to use, so the work is not "
                    + "taken off the critical path -- it is handed sideways, and costs a little "
                    + "extra on the way.",
                "RECOMMENDATION: set enabled=false. You are unlikely to gain anything here and may "
                    + "lose a little. Nothing is switched off automatically.");
        }
        return List.of(
            "This machine reports " + availableProcessors + " processor(s), giving " + workers
                + " worker thread(s). That is the smallest useful size.",
            "Expect a small benefit at best: the worker has little room to run in parallel with the "
                + "server thread, and PathWeaver's own main-thread work is not free.",
            "If /pathweaver status shows little of the work being used, enabled=false is a "
                + "reasonable choice.");
    }

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
                    + "Measured at 0, {}% of dispatched searches were never installed within the "
                    + "capture window. The value is enforced as configured; raise it to about {} to "
                    + "get any benefit.",
                c.stalenessMoveThreshold, 91.8, MIN_USEFUL_STALENESS_BLOCKS * 4);
        }
        int threads = c.resolvedPoolThreads();
        // Warn rather than cap. Enforcing a per-worker ratio was tried and reverted: at two workers
        // it turned the shipped 256 into 64, which measured 31% worse p99 -- the metric this mod
        // exists to improve -- to save worker work that was never the constraint.
        int depth = c.maxInFlight / Math.max(1, threads);
        if (depth > dev.pathweaver.async.PathWorkerPool.DEPTH_PER_WORKER) {
            PathWeaver.LOG.warn("maxInFlight={} across {} worker thread(s) is about {} queued per "
                    + "worker. Measured up to about {} per worker still landed in time; deeper than "
                    + "that, a growing share of finished searches arrives after its mob has asked "
                    + "again. The value is enforced exactly as configured -- lower maxInFlight or "
                    + "raise poolThreads if the log below reports little of the work being used.",
                c.maxInFlight, threads, depth,
                dev.pathweaver.async.PathWorkerPool.DEPTH_PER_WORKER);
        }
        if (c.poolThreads > 0 && c.poolThreads > Runtime.getRuntime().availableProcessors()) {
            PathWeaver.LOG.warn("poolThreads={} exceeds the {} processors this machine reports. The "
                    + "extra threads cannot run in parallel and compete with the server thread for "
                    + "the same cores. 0 picks a size automatically.",
                c.poolThreads, Runtime.getRuntime().availableProcessors());
        }
        if (c.maxInFlight > MAX_USEFUL_IN_FLIGHT) {
            PathWeaver.LOG.warn("maxInFlight={} is above the measured useful range. It is an admission "
                    + "bound rather than a buffer: a deeper queue makes each result land later, and a "
                    + "result that arrives after its mob has asked again is superseded. Measured on a "
                    + "371-mod pack, {} left 90.7% of dispatched searches uninstalled within the "
                    + "capture window and {} left effectively all of them. The value is enforced as "
                    + "configured; {} is the shipped default.",
                c.maxInFlight, 1024, 4096, MAX_USEFUL_IN_FLIGHT);
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
    /**
     * Searches that completed and proved no route exists, excluded from the waste ratio.
     *
     * <p>{@code discardedCount()} and {@code RequestOutcome} both go to some trouble to keep
     * {@code NO_PATH} out of "wasted work" — it is a search that succeeded with an empty answer. The
     * sampler put it straight back in by dividing installs by raw dispatches, so a pack whose mobs
     * routinely target positions outside the search region would be told its pool was the bottleneck
     * and pointed at {@code maxInFlight} and {@code poolThreads}. Neither would move the number,
     * because nothing was late.
     */
    private long lastWasteNoPath;
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
        long installedNow = installedCount();
        long noPathNow = outcomeCount(dev.pathweaver.async.RequestOutcome.NO_PATH);
        long windowInstalled = installedNow - lastWasteInstalled;
        long windowNoPath = noPathNow - lastWasteNoPath;
        // Judge installs against searches that could have produced a path, not against every search.
        long windowDispatched = Math.max(0L, (dispatchedNow - lastWasteDispatched) - windowNoPath);
        lastWasteDispatched = dispatchedNow;
        lastWasteInstalled = installedNow;
        lastWasteNoPath = noPathNow;
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
            consecutiveWastedWindows, pool.maxInFlight(), pool.threads());
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
        lastWasteInstalled = installedCount();
        wasteReported = false;
        lastWasteNoPath = outcomeCount(dev.pathweaver.async.RequestOutcome.NO_PATH);
    }

    /** Test seam. */
    boolean wasteReported() { return wasteReported; }
}
