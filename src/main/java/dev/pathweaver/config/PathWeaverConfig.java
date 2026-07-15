package dev.pathweaver.config;

import me.shedaniel.autoconfig.ConfigData;
import me.shedaniel.autoconfig.annotation.Config;
import me.shedaniel.autoconfig.annotation.ConfigEntry;

/**
 * Runtime config the engine reads, and the Cloth AutoConfig model (persists to
 * {@code config/pathweaver.json}, GUI via ModMenu when present). Kept free of MC types so it stays
 * unit-testable; Cloth annotations + the marker {@link ConfigData} interface are inert at runtime.
 */
@Config(name = "pathweaver")
public class PathWeaverConfig implements ConfigData {
    public static final int MAX_POOL_THREADS = 64;
    public static final int MAX_IN_FLIGHT = 4096;
    public static final int MAX_REPATH_TOLERANCE_BLOCKS = 64;
    public static final double MAX_STALENESS_MOVE_THRESHOLD = 1024.0;

    @ConfigEntry.Gui.Tooltip
    public boolean asyncEnabled = true;

    @ConfigEntry.Gui.Tooltip
    public boolean repathElisionEnabled = true;

    @ConfigEntry.Gui.Tooltip
    public int poolThreads = 0;          // 0 = auto (cores/4)

    @ConfigEntry.Gui.Tooltip
    public int maxInFlight = 256;

    @ConfigEntry.Gui.Tooltip
    public boolean distanceThrottleEnabled = false;  // opt-in: makes far mobs dumber

    @ConfigEntry.Gui.Tooltip
    public boolean syncFallbackOnly = false;         // panic switch: never dispatch async

    @ConfigEntry.Gui.Tooltip
    public int repathToleranceBlocks = 0;

    @ConfigEntry.Gui.Tooltip
    public double stalenessMoveThreshold = 4.0;      // blocks moved since dispatch -> discard

    private static PathWeaverConfig INSTANCE = new PathWeaverConfig();
    public static PathWeaverConfig get() { return INSTANCE; }
    public static void set(PathWeaverConfig c) {
        PathWeaverConfig normalized = c == null ? new PathWeaverConfig() : c;
        normalized.validatePostLoad();
        INSTANCE = normalized;
    }

    /**
     * Normalize persisted/GUI values before runtime services consume them. Invalid config must reduce
     * coverage or capacity, never make executor construction fail during server startup.
     */
    @Override
    public void validatePostLoad() {
        poolThreads = Math.clamp(poolThreads, 0, MAX_POOL_THREADS);
        maxInFlight = Math.clamp(maxInFlight, 1, MAX_IN_FLIGHT);
        repathToleranceBlocks = Math.clamp(
            repathToleranceBlocks, 0, MAX_REPATH_TOLERANCE_BLOCKS);
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
