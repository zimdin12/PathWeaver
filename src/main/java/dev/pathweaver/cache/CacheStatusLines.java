package dev.pathweaver.cache;

import dev.pathweaver.config.PathWeaverConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * What the route cache did, written so an operator can act on it.
 *
 * <p>Four kinds of number appear here and they are never added together.
 *
 * <ul>
 *   <li>MEASURED. A search that did not run: {@code served}. This is the only saving.</li>
 *   <li>HYPOTHETICAL. A hit that could not be spent: {@code wouldServe}. Either the cache was
 *       measuring when the entry was stored, so no route was kept, or it is measuring now. Real hits,
 *       no saving.</li>
 *   <li>EFFECTIVE. What the running cache is doing, such as the capacity chosen at server start.</li>
 *   <li>SAVED. What the settings file says, which may be a change that needs a restart.</li>
 * </ul>
 *
 * <p>The previous version printed {@code served + wouldServe} under "searches skipped" whenever the
 * mode was SERVE. That sum is wrong in both directions at once: observations made earlier in SHADOW
 * skipped nothing, and hits on entries holding no route skipped nothing either, yet an operator
 * reading the line would price the feature on it. Capacity had the same shape of error, printing the
 * saved number while the running cache used the one it was built with.
 *
 * <p>Extracted from the command so it can be asserted without a server, and because a status block
 * with rules of its own is not the command's job.
 */
public final class CacheStatusLines {

    private CacheStatusLines() { }

    public static List<String> render(PathWeaverConfig config, PathCache cache) {
        if (!config.resultCacheActive()) return List.of("  route cache: off");

        PathCache.Counters counters = cache.counters();
        List<String> out = new ArrayList<>();
        out.add("  route cache: " + (config.resultCacheServes() ? "serving" : "measuring only")
            + "   entries=" + cache.size() + "/" + cache.capacity()
            + "   maxAge=" + config.resultCacheMaxAgeTicks + " ticks");
        if (cache.capacity() != config.resultCacheMaxEntries) {
            out.add("  §7Capacity is fixed when the server starts. The settings file now says "
                + config.resultCacheMaxEntries + "; that takes effect on the next restart.");
        }

        // Measured, on its own line, with its own share. Zero here means zero saving, whatever the
        // hit count says.
        out.add("    §a" + counters.served + "§r  searches skipped" + share(counters.served, counters));
        out.add("    §e" + counters.wouldServe + "§r  hits that saved nothing"
            + share(counters.wouldServe, counters) + " §7-- "
            + (config.resultCacheServes()
                ? "stored while the cache was only measuring, so no route was kept"
                : "the cache is measuring; switch to SERVE to spend these"));

        out.add("    §7" + counters.blockOnlyHits + "  matched except for the mob's exact position "
            + "-- what a looser key would add, if it were safe");
        out.add("    §7" + counters.stored + " stored, " + counters.refusedTerrainMoved
            + " refused because the ground moved mid-search, " + counters.expired + " expired, "
            + counters.terrainChanged + " dropped when the terrain changed, "
            + counters.lookups + " lookups");
        if (!config.resultCacheServes()) {
            out.add("  §7The cache is measuring, not serving. Set resultCacheMode to SERVE to spend "
                + "the amber number above; it takes effect immediately, no restart.");
        }
        return List.copyOf(out);
    }

    /** A share of lookups, or nothing at all when there were none to take a share of. */
    private static String share(long count, PathCache.Counters counters) {
        if (counters.lookups <= 0L) return "";
        return String.format(Locale.ROOT, " §7(%.1f%% of lookups)", 100.0 * count / counters.lookups);
    }
}
