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
    public static final int CURRENT_CONFIG_VERSION = 2;
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


    @ConfigEntry.Gui.Tooltip(count = 2)
    @ConfigEntry.Gui.RequiresRestart
    @ConfigEntry.Category("performance")
    public int poolThreads = 0;          // 0 = auto (cores/4)

    @ConfigEntry.Gui.Tooltip(count = 4)
    @ConfigEntry.Gui.RequiresRestart
    @ConfigEntry.Category("performance")
    public int maxInFlight = 256;


    @ConfigEntry.Gui.Tooltip(count = 3)
    @ConfigEntry.Category("repath")
    public int repathToleranceBlocks = 0;

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
    public int workerFailureLimit = 3;

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
    public static PathWeaverConfig get() { return INSTANCE; }
    public static void set(PathWeaverConfig c) {
        PathWeaverConfig normalized = c == null ? new PathWeaverConfig() : c;
        normalized.validatePostLoad();
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

    public static InteractionResult onSave(
            ConfigHolder<PathWeaverConfig> holder, PathWeaverConfig config) {
        set(config);
        return InteractionResult.PASS;
    }

    /** A limit past this is indistinguishable from "off" and only invites a typo that reads as armed. */
    @ConfigEntry.Gui.Excluded
    @ConfigEntry.Category("general")
    public static final int MAX_WORKER_FAILURE_LIMIT = 1000;

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
        // Clamped here with every other int, because a hand-edited negative limit would otherwise
        // read as "off" through one code path and "trip immediately" through another.
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
        int resolved = configuredThreads > 0
            ? configuredThreads
            : Math.max(1, availableProcessors / 4);
        return Math.clamp(resolved, 1, MAX_POOL_THREADS);
    }
}
