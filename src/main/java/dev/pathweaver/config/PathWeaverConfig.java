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
    public int repathToleranceBlocks = 1;

    @ConfigEntry.Gui.Tooltip
    public double stalenessMoveThreshold = 4.0;      // blocks moved since dispatch -> discard

    private static PathWeaverConfig INSTANCE = new PathWeaverConfig();
    public static PathWeaverConfig get() { return INSTANCE; }
    public static void set(PathWeaverConfig c) { INSTANCE = c; }

    public int resolvedPoolThreads() {
        return poolThreads > 0 ? poolThreads
             : Math.max(1, Runtime.getRuntime().availableProcessors() / 4);
    }
}
