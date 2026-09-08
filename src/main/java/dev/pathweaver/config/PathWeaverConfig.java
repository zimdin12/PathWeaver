package dev.pathweaver.config;

import java.util.ArrayList;
import java.util.List;
import me.shedaniel.autoconfig.ConfigData;
import me.shedaniel.autoconfig.ConfigHolder;
import me.shedaniel.autoconfig.annotation.Config;
import me.shedaniel.autoconfig.annotation.ConfigEntry;
import net.minecraft.world.InteractionResult;

/**
 * Runtime config the engine reads, and the Cloth AutoConfig model (persists to
 * {@code config/pathweaver.json}, GUI via ModMenu when present). Kept free of gameplay/world types so
 * it stays unit-testable; Cloth annotations + the marker {@link ConfigData} interface are inert at runtime.
 */
@Config(name = "pathweaver")
public class PathWeaverConfig implements ConfigData {
    @ConfigEntry.Gui.Excluded
    @ConfigEntry.Category("general")
    /**
     * 3 since the repath-tolerance migration. A bump is not free: the serializer fails closed on any
     * version it does not know, and failing closed sets {@code enabled=false}, so an older jar
     * reading a config this one wrote will switch itself off rather than guess. That is the correct
     * direction to fail and it is the reason a bump only happens when a stored VALUE has to change,
     * never merely because a field was added.
     */
    public static final int CURRENT_CONFIG_VERSION = 3;
    @ConfigEntry.Gui.Excluded
    @ConfigEntry.Category("general")
    public static final int MAX_POOL_THREADS = 64;
    @ConfigEntry.Gui.Excluded
    @ConfigEntry.Category("general")
    public static final int MAX_IN_FLIGHT = 4096;
    @ConfigEntry.Gui.Excluded
    @ConfigEntry.Category("general")
    public static final int MAX_REPATH_TOLERANCE_BLOCKS = 64;
    @ConfigEntry.Gui.Excluded
    @ConfigEntry.Category("general")
    public static final int MAX_RESULT_AGE_TICKS = 1200;
    @ConfigEntry.Gui.Excluded
    @ConfigEntry.Category("general")
    public static final double MAX_STALENESS_MOVE_THRESHOLD = 1024.0;
    @ConfigEntry.Gui.Excluded
    @ConfigEntry.Category("general")
    public static final int MAX_RESULT_CACHE_ENTRIES = 65_536;

    @ConfigEntry.Gui.Tooltip(count = 2)
    @ConfigEntry.Category("general")
    public boolean enabled = true;

    @ConfigEntry.Gui.Excluded
    @ConfigEntry.Category("general")
    public int configVersion = CURRENT_CONFIG_VERSION;

    @ConfigEntry.Gui.Tooltip(count = 4)
    @ConfigEntry.Category("general")
    public boolean allowModdedMobAsync = false;

    /**
     * How much risk to accept from mods that modify pathfinding. See {@link CompatibilityTier}.
     *
     * <p>Defaults to {@link CompatibilityTier#UNSAFE}: no compatibility checking at all. That is a
     * deliberate project decision and it is worth stating plainly rather than burying, because the
     * safer value is the one this field does not ship with.
     *
     * <p>The reason is that {@code AUDITED} does nothing on the packs people actually run. It
     * honours bytecode audits and one bounded call sample, and any mod outside that evidence denies
     * every movement family. Measured on a 222-mod pack: {@code AUDITED} left <strong>0 of 187</strong>
     * mob types eligible, {@code UNSAFE} left all 187. A stricter tier existed in 0.3 and was removed
     * in 0.4.0 for being worse still — it admitted only structural proofs, and since the exemption
     * covering Fabric API's own interaction module is a sample rather than a proof, it denied every
     * install containing Fabric API, which this mod requires. Shipping {@code AUDITED} by default
     * meant shipping a mod that installs, does nothing, and is indistinguishable from a broken one.
     *
     * <p>What is being accepted: uninspected third-party code runs on worker threads. The most
     * likely failure is quiet — a wrong path or a torn read, which a user will probably never
     * report. It is not the only possible one: nothing has been proven about code that was never
     * inspected, so a crash or a corrupted world is not excluded, only less likely.
     * This javadoc previously said "not a crash and not a corrupt region file", which
     * {@link CompatibilityTier#UNSAFE} flatly contradicted, and the reassuring version was the one
     * being used to argue for this default. Nothing here is evidence that it is safe; it is a choice
     * to trade an unproven risk for a mod that works on arrival, taken because the alternative was a
     * mod that never works at all.
     *
     * <p>Two things keep it honest. The startup log prints a blocking {@code WARN} block naming
     * every unaudited mod that is now running on workers, so this is never silent. And
     * {@code AUDITED} is one setting away, with {@link #trustedMods} between them for anyone who
     * wants to accept named mods rather than all of them.
     */
    @ConfigEntry.Gui.Tooltip(count = 5)
    @ConfigEntry.Category("general")
    @ConfigEntry.Gui.RequiresRestart
    @ConfigEntry.Gui.EnumHandler(option = ConfigEntry.Gui.EnumHandler.EnumDisplayOption.DROPDOWN)
    public CompatibilityTier compatibilityTier = CompatibilityTier.UNSAFE;

    /**
     * Mod ids whose pathfinding denials to ignore, while the scan stays armed for everything else.
     *
     * <p>The tier is all-or-nothing: {@code UNSAFE} waives every denial, permanently, including for
     * mods installed next month. On a heavily-modded pack that is the only way to make this mod do
     * anything, which turns an informed decision about nine known mods into a blanket one about
     * every mod that will ever touch pathfinding.
     *
     * <p>This is the scoped version. Naming a mod here accepts exactly that mod's risk and leaves
     * the rest of the scan doing its job, so a new mod that modifies pathfinding still switches the
     * affected families off and still says so at world start.
     *
     * <p>It is not a safety feature. Anything named here runs on worker threads without having been
     * audited, which is the same exposure {@code UNSAFE} gives — just aimed. Matching is by mod id
     * only, so an entry keeps applying after that mod updates and changes what its mixins do; the
     * audited exemptions are pinned to exact artifact hashes precisely because this is not.
     */
    @ConfigEntry.Gui.Tooltip(count = 4)
    @ConfigEntry.Category("general")
    @ConfigEntry.Gui.RequiresRestart
    public List<String> trustedMods = new ArrayList<>();


    /**
     * Worker threads. 0 means one per four CPU threads.
     *
     * <p>Raising this is almost never the fix, and the profile says why: on a 32-thread machine
     * running a 317-mod pack in a busy village, the pool was parked for 639,628 ms of 646,816 ms
     * sampled. 99% idle. Threads are not the constraint; searches are admitted against
     * {@link #maxInFlight} and arrive in bursts that a handful of workers absorb.
     *
     * <p>The ceiling stays high because a genuinely saturated pool is possible and refusing to let
     * an operator try is worse than letting them measure it. But the tooltip now tells them what to
     * look at first rather than implying the number is a performance dial.
     */
    @ConfigEntry.Gui.Tooltip(count = 4)
    @ConfigEntry.Gui.RequiresRestart
    @ConfigEntry.Category("performance")
    public int poolThreads = 0;          // 0 = auto (cores/4)

    @ConfigEntry.Gui.Tooltip(count = 4)
    @ConfigEntry.Gui.RequiresRestart
    @ConfigEntry.Category("performance")
    public int maxInFlight = 256;


    /**
     * Offload the villager-brain movement sink, at the cost of one tick before the mob sets off.
     *
     * <p>Brain mobs — villagers, piglins, axolotls, frogs, allays — never call {@code moveTo(x,y,z)}.
     * All their movement pathing goes through {@code MoveToTargetSink.tryComputePath}, which calls
     * {@code createPath} and reads the answer immediately, so the four ordinary dispatch sites never
     * see them. On a profile of the reference pack this was the single largest slice of A* left on
     * the server thread that could be moved at all.
     *
     * <p>The cost is real and is why this is a setting rather than unconditional behaviour: on the
     * tick a search is dispatched the sink reports "no path yet", so the behaviour does not start
     * until the following tick. Nothing else changes — the landed path is handed to vanilla's own
     * reachability and memory logic untouched.
     */
    // No @Gui.RequiresRestart: both read sites evaluate PathWeaverConfig.get().brainSinkAsync per
    // call, and a settings save replaces the singleton immediately, so the toggle takes effect at
    // once. Claiming a restart it does not need is the same defect as omitting one it does --
    // compatibilityTier's restart is real, enforced by the scan-time freeze, and this one was not.
    @ConfigEntry.Gui.Tooltip(count = 3)
    @ConfigEntry.Category("performance")
    public boolean brainSinkAsync = true;

    /**
     * Whether a route one mob computed may answer another mob's identical request.
     *
     * <p>Ships on {@link PathCacheMode#SHADOW}: measured, never served. Sharing only pays if mobs
     * really do repeat the same search from the same block, and that is a property of the pack
     * rather than of this code, so the shipped default is the one that produces the number instead
     * of the one that assumes it. {@code /pathweaver status} reports what serving would have
     * returned.
     */
    @ConfigEntry.Gui.Tooltip(count = 4)
    @ConfigEntry.Category("performance")
    @ConfigEntry.Gui.EnumHandler(option = ConfigEntry.Gui.EnumHandler.EnumDisplayOption.DROPDOWN)
    public PathCacheMode resultCacheMode = PathCacheMode.SHADOW;

    /**
     * How long a cached route stays offerable, in ticks. Two seconds by default.
     *
     * <p>This is not the safety limit; terrain changes along the route invalidate it whatever this
     * says. It is a bound on how far the world can drift in ways the cache cannot see, such as a
     * door another mob has since opened or a shorter way that has appeared off the route.
     */
    @ConfigEntry.Gui.Tooltip(count = 3)
    @ConfigEntry.Category("performance")
    public int resultCacheMaxAgeTicks = 40;

    /** Routes kept before the least recently used is dropped. */
    @ConfigEntry.Gui.Tooltip(count = 2)
    @ConfigEntry.Gui.RequiresRestart
    @ConfigEntry.Category("performance")
    public int resultCacheMaxEntries = 4096;

    @ConfigEntry.Gui.Tooltip(count = 3)
    @ConfigEntry.Category("repath")
    // Default 1, not 0.
    //
    // Zero meant the elision never ran at all, so the cheapest possible win was off unless someone
    // found the setting. Measured on a 317-mod pack, total A* work rose 20% with the mod enabled,
    // because mobs that get paths move more and re-path constantly; MCA villagers re-target almost
    // every tick. One block is the smallest value that does anything, and it only ever reuses a
    // path that is still valid for a target that has barely moved.
    public int repathToleranceBlocks = 1;

    @ConfigEntry.Gui.Tooltip(count = 3)
    @ConfigEntry.Category("repath")
    public double stalenessMoveThreshold = 4.0;      // blocks moved since dispatch -> discard

    @ConfigEntry.Gui.Tooltip(count = 2)
    @ConfigEntry.Category("repath")
    public int maxResultAgeTicks = 40;

    /**
     * Search failures for one movement family, within {@link #workerFailureWindowTicks}, before that
     * family stops dispatching for the rest of the session.
     *
     * <p>{@code 0} turns the breaker off: failures are still counted, logged and attributed, they are
     * just never acted on. That is 0.6.0's behaviour and a legitimate choice when benchmarking.
     *
     * <p>No {@code configVersion} bump came with this field, deliberately. The serializer throws on
     * any version but 2, 1 or 0, and a load failure installs fail-closed defaults that set
     * {@code enabled = false} -- so bumping the version would have switched the mod off for every
     * existing install. An absent key simply keeps this initializer.
     */
    @ConfigEntry.Gui.Tooltip(count = 4)
    @ConfigEntry.Category("general")
    public int workerFailureLimit = DEFAULT_WORKER_FAILURE_LIMIT;

    /**
     * The window those failures must fall inside, in ticks. {@code 0} means "never decays".
     *
     * <p>Windowed rather than cumulative because a counter that never decays converges on a certain
     * trip given enough uptime, and this project's own Lithium audit describes a concurrent-resize
     * exception as an expected, contained event. Three of those over a fortnight is not an
     * incompatibility; three in a minute is. A false trip is not a free no-op -- being vanilla is
     * what the user installed this mod to stop.
     */
    @ConfigEntry.Gui.Tooltip(count = 2)
    @ConfigEntry.Category("general")
    public int workerFailureWindowTicks = 1200;

    @ConfigEntry.Gui.Excluded
    @ConfigEntry.Category("general")
    private static volatile PathWeaverConfig INSTANCE = new PathWeaverConfig();

    /**
     * Bumped on every published configuration, so a consumer can tell that policy moved under it.
     *
     * <p>This exists for the route cache. Its section clock only observes block changes while the
     * cache is active, but its stored routes outlive a setting change, so switching the cache or the
     * master switch off, changing a block, and switching back inside a route's lifetime would serve
     * a route across a change nothing recorded. The cache cannot be cleared from here: settings are
     * published from whichever thread saved them, the cache's maps are not concurrent, and clearing
     * one while the server thread reads it is a worse bug than the one being fixed.
     *
     * <p>So the barrier is a number the cache checks on ITS thread, before it acts on the new policy.
     * Every publication path funnels through {@link #set}, including a save that hands back the same
     * object, so incrementing here covers all of them without anyone having to remember to.
     *
     * <p>It allocates the number; the number then travels ON the published object, so consumers
     * never read it from here.
     *
     * <p>Annotated the way every static in this class is: AutoConfig reflects over declared fields
     * and would otherwise put this on the settings screen and crash Save on a final field.
     */
    @ConfigEntry.Gui.Excluded
    @ConfigEntry.Category("general")
    private static final java.util.concurrent.atomic.AtomicLong POLICY_GENERATION =
        new java.util.concurrent.atomic.AtomicLong();

    @ConfigEntry.Gui.Excluded
    @ConfigEntry.Category("general")
    private transient long generation;

    public static PathWeaverConfig get() { return INSTANCE; }

    /**
     * Which published configuration THIS object is. Only equality across calls is meaningful.
     *
     * <p>An instance method, not a static one, and that is the whole point. A consumer that reads the
     * settings and the generation separately can be interrupted by a publication between the two
     * reads, and one of the two orders is wrong: take the generation first and the settings second,
     * and the cache sees an unchanged number while acting on new settings, so the barrier never
     * fires. Reading them off one object cannot interleave, so the question does not arise and no
     * caller has to remember an ordering rule.
     */
    public long generation() { return generation; }

    public static void set(PathWeaverConfig c) {
        PathWeaverConfig normalized = c == null ? new PathWeaverConfig() : c;
        normalized.validatePostLoad();
        // Stamped BEFORE publication, so the object is never visible without its own generation.
        normalized.generation = POLICY_GENERATION.incrementAndGet();
        INSTANCE = normalized;
    }

    /** Keep pathfinding synchronous if persisted configuration cannot be registered or loaded. */
    public static void installFailClosedDefaults() {
        PathWeaverConfig fallback = new PathWeaverConfig();
        fallback.enabled = false;
        set(fallback);
    }

    /** Cloth substitutes enabled defaults after deserialize failure; do not publish those as success. */
    public static void publishLoaded(PathWeaverConfig loaded, boolean loadFailed) {
        if (loadFailed) installFailClosedDefaults();
        else set(loaded);
    }

    /**
     * True when the operator asked for no compatibility checking at all.
     *
     * <p>Exposed as a primitive on purpose. The dispatch interceptor is a mixin applied to a vanilla
     * class during early transformation; naming {@link CompatibilityTier} in its bytecode forces
     * that enum -- and, through it, the Cloth GUI interface it implements for its settings label --
     * to resolve at that moment, which stalls server startup. Reading the tier behind a boolean
     * keeps that resolution on the ordinary configuration path.
     *
     * <p>Reports the tier frozen at scan time, not the persisted field. This and the two accessors
     * below deliberately ignore a tier saved from the settings screen until the next launch; see
     * {@link dev.pathweaver.gate.ActiveCompatibilityPolicy} for why a live tier was incoherent.
     */
    public boolean bypassesCompatibilityScan() {
        return dev.pathweaver.gate.ActiveCompatibilityPolicy.bypassesScan();
    }

    /**
     * True when exemptions resting on an audit, rather than on proven inertness, may be honoured.
     *
     * <p>Primitive for the same reason as {@link #bypassesCompatibilityScan()}.
     */
    public boolean allowsAuditedCompatibility() {
        return dev.pathweaver.gate.ActiveCompatibilityPolicy.allowsAudited();
    }

    /**
     * True when a mob defined by a mod may path off-thread.
     *
     * <p>{@link CompatibilityTier#UNSAFE} implies this. The origin gate is a compatibility check like
     * any other — it refuses mod-defined mob classes because their navigation overrides have not
     * been inspected — so leaving it armed under "ignore every check" kept most of a heavily-modded
     * pack's mobs synchronous while reporting that nothing was being checked. The dedicated flag is
     * retained so the bypass is still reachable from the stricter tiers.
     *
     * <p>Primitive for the same reason as {@link #bypassesCompatibilityScan()}: the caller is a
     * mixin applied during early transformation and must not name the tier enum.
     */
    public boolean moddedMobAsyncAllowed() {
        return allowModdedMobAsync || dev.pathweaver.gate.ActiveCompatibilityPolicy.bypassesScan();
    }

    /**
     * True when the shared result cache should be consulted at all.
     *
     * <p>Primitive for the same reason as {@link #bypassesCompatibilityScan()}: the caller is a
     * mixin applied during early transformation, and naming {@link PathCacheMode} in its bytecode
     * would resolve the Cloth settings-screen interface that enum implements at that moment.
     */
    public boolean resultCacheActive() {
        return resultCacheMode != PathCacheMode.OFF;
    }

    /** True when a cache hit may actually be given to a mob rather than only counted. */
    public boolean resultCacheServes() {
        return resultCacheMode == PathCacheMode.SERVE;
    }

    /**
     * True when world changes are being recorded for the shared cache.
     *
     * <p>The one definition of that question. The block-change hook runs on every visible block
     * update in the world, so it has to decide quickly whether to record anything, and both switches
     * silence it: with the mod off nothing should touch the world, and with the cache off there is
     * nothing to keep the records for.
     *
     * <p>The consequence is why {@code PathCache} has a policy barrier. Routes stored earlier outlive
     * a period when this is false, so a block broken during that period leaves no trace, and serving
     * such a route afterwards would walk a mob through terrain nobody watched. Whoever changes this
     * predicate is changing how wide that blind period is.
     */
    public boolean recordsBlockChanges() {
        return enabled && resultCacheActive();
    }

    public static InteractionResult onSave(
            ConfigHolder<PathWeaverConfig> holder, PathWeaverConfig config) {
        set(config);
        return InteractionResult.PASS;
    }

    /** A limit past this is indistinguishable from "off" and only invites a typo that reads as armed. */
    @ConfigEntry.Gui.Excluded
    @ConfigEntry.Category("general")
    public static final int MAX_WORKER_FAILURE_LIMIT = 1000;
    /** Shipped default, named because the invalid-input repair below has to land on it. */
    @ConfigEntry.Gui.Excluded
    @ConfigEntry.Category("general")
    public static final int DEFAULT_WORKER_FAILURE_LIMIT = 3;

    /** One real-time hour of ticks. Beyond this a window is a cumulative count with extra steps. */
    @ConfigEntry.Gui.Excluded
    @ConfigEntry.Category("general")
    public static final int MAX_WORKER_FAILURE_WINDOW_TICKS = 72_000;

    /**
     * Normalize persisted/GUI values before runtime services consume them. Invalid config must reduce
     * coverage or capacity, never make executor construction fail during server startup.
     */
    @Override
    public void validatePostLoad() {
        configVersion = CURRENT_CONFIG_VERSION;
        poolThreads = Math.clamp(poolThreads, 0, MAX_POOL_THREADS);
        maxInFlight = Math.clamp(maxInFlight, 1, MAX_IN_FLIGHT);
        repathToleranceBlocks = Math.clamp(
            repathToleranceBlocks, 0, MAX_REPATH_TOLERANCE_BLOCKS);
        maxResultAgeTicks = Math.clamp(maxResultAgeTicks, 1, MAX_RESULT_AGE_TICKS);
        if (resultCacheMode == null) resultCacheMode = PathCacheMode.SHADOW;
        resultCacheMaxAgeTicks = Math.clamp(resultCacheMaxAgeTicks, 1, MAX_RESULT_AGE_TICKS);
        resultCacheMaxEntries = Math.clamp(resultCacheMaxEntries, 1, MAX_RESULT_CACHE_ENTRIES);
        // Clamped here with every other int, because a hand-edited negative limit would otherwise
        // read as "off" through one code path and "trip immediately" through another.
        // Zero is a documented choice: never switch a family off. A NEGATIVE is not a choice, and
        // clamping it to zero silently landed it on that documented meaning -- turning the
        // family-shutdown safety net off for the session while the GUI and /pathweaver status both
        // showed a legal value. Invalid input goes to the default instead.
        //
        // Other fields also floor at 0 -- poolThreads, repathToleranceBlocks and
        // workerFailureWindowTicks -- and for them 0 is the STRICT end, so landing there is safe.
        // This was the only one where the documented meaning of 0 was "switch the safety net off".
        if (workerFailureLimit < 0) workerFailureLimit = DEFAULT_WORKER_FAILURE_LIMIT;
        workerFailureLimit = Math.clamp(workerFailureLimit, 0, MAX_WORKER_FAILURE_LIMIT);
        workerFailureWindowTicks = Math.clamp(
            workerFailureWindowTicks, 0, MAX_WORKER_FAILURE_WINDOW_TICKS);
        if (Double.isNaN(stalenessMoveThreshold) || stalenessMoveThreshold < 0.0) {
            stalenessMoveThreshold = 0.0;
        } else if (!Double.isFinite(stalenessMoveThreshold)
                || stalenessMoveThreshold > MAX_STALENESS_MOVE_THRESHOLD) {
            stalenessMoveThreshold = MAX_STALENESS_MOVE_THRESHOLD;
        }
    }

    public int resolvedPoolThreads() {
        return resolvePoolThreads(poolThreads, Runtime.getRuntime().availableProcessors());
    }

    static int resolvePoolThreads(int configuredThreads, int availableProcessors) {
        // FLOOR OF TWO, not one.
        //
        // cores/4 gave a single worker on a 4-thread machine, and one worker serialises every
        // search behind the one in front. Results have a limited useful life (maxResultAgeTicks),
        // so a queue that forms behind a single worker turns into discarded work rather than
        // slower work. Two is the smallest size where a slow search does not block every other mob.
        //
        // NOT cores/2. On a 32-thread machine the pool already measured 99% idle at 8 threads
        // (639,628 ms parked of 646,816 ms sampled), so doubling it adds idle threads, and every
        // extra concurrent search is another thread reading live chunk data, which is the one
        // unsafety this design already carries. Small machines were the real gap; big ones were not.
        int resolved = configuredThreads > 0
            ? configuredThreads
            : Math.max(2, availableProcessors / 4);
        return Math.clamp(resolved, 1, MAX_POOL_THREADS);
    }
}
