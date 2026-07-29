package dev.pathweaver.config;

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

    @ConfigEntry.Gui.Tooltip
    @ConfigEntry.Category("general")
    public boolean enabled = true;

    @ConfigEntry.Gui.Excluded
    @ConfigEntry.Category("general")
    public int configVersion = CURRENT_CONFIG_VERSION;

    @ConfigEntry.Gui.Tooltip
    @ConfigEntry.Category("general")
    public boolean allowModdedMobAsync = false;

    /**
     * How much risk to accept from mods that modify pathfinding. See {@link CompatibilityTier}.
     *
     * <p>Defaults to {@link CompatibilityTier#STRICT}, which only runs off-thread where a worker
     * provably cannot observe the foreign change. Raising it is how a server owner trades a
     * possible wrong path for tick headroom on a pack the strict rule would otherwise disable.
     */
    @ConfigEntry.Gui.Tooltip
    @ConfigEntry.Category("general")
    @ConfigEntry.Gui.RequiresRestart
    @ConfigEntry.Gui.EnumHandler(option = ConfigEntry.Gui.EnumHandler.EnumDisplayOption.BUTTON)
    public CompatibilityTier compatibilityTier = CompatibilityTier.STRICT;

    @ConfigEntry.Gui.Tooltip
    @ConfigEntry.Category("general")
    public boolean repathElisionEnabled = true;

    @ConfigEntry.Gui.Tooltip
    @ConfigEntry.Gui.RequiresRestart
    @ConfigEntry.Category("performance")
    public int poolThreads = 0;          // 0 = auto (cores/4)

    @ConfigEntry.Gui.Tooltip
    @ConfigEntry.Gui.RequiresRestart
    @ConfigEntry.Category("performance")
    public int maxInFlight = 256;


    @ConfigEntry.Gui.Tooltip
    @ConfigEntry.Category("repath")
    public int repathToleranceBlocks = 0;

    @ConfigEntry.Gui.Tooltip
    @ConfigEntry.Category("repath")
    public double stalenessMoveThreshold = 4.0;      // blocks moved since dispatch -> discard

    @ConfigEntry.Gui.Tooltip
    @ConfigEntry.Category("repath")
    public int maxResultAgeTicks = 40;

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
     */
    public boolean bypassesCompatibilityScan() {
        return compatibilityTier.bypassesScan();
    }

    /**
     * True when a mob defined by a mod may path off-thread.
     *
     * <p>{@link CompatibilityTier#ALL} implies this. The origin gate is a compatibility check like
     * any other — it refuses mod-defined mob classes because their navigation overrides have not
     * been inspected — so leaving it armed under "ignore every check" kept most of a heavily-modded
     * pack's mobs synchronous while reporting that nothing was being checked. The dedicated flag is
     * retained so the bypass is still reachable from the stricter tiers.
     *
     * <p>Primitive for the same reason as {@link #bypassesCompatibilityScan()}: the caller is a
     * mixin applied during early transformation and must not name the tier enum.
     */
    public boolean moddedMobAsyncAllowed() {
        return allowModdedMobAsync || compatibilityTier.bypassesScan();
    }

    public static InteractionResult onSave(
            ConfigHolder<PathWeaverConfig> holder, PathWeaverConfig config) {
        set(config);
        return InteractionResult.PASS;
    }

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
