package dev.pathweaver.cache;

import dev.pathweaver.config.PathCacheMode;
import dev.pathweaver.config.PathWeaverConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The status block must not report a saving it did not make.
 *
 * <p>{@code served + wouldServe} was printed under "searches skipped" whenever the mode was SERVE.
 * Both halves of that sum are wrong for the label: observations made while the cache was measuring
 * skipped nothing, and a hit on an entry holding no route skips nothing either. An operator deciding
 * whether the feature is worth switching on reads exactly that line.
 *
 * <p>Capacity had the same shape. It is chosen when the server starts, so the number in the settings
 * file can be one nothing is using yet.
 */
class CacheStatusLinesTest {

    private static PathWeaverConfig config(PathCacheMode mode, int savedCapacity) {
        PathWeaverConfig config = new PathWeaverConfig();
        config.resultCacheMode = mode;
        config.resultCacheMaxEntries = savedCapacity;
        return config;
    }

    private static PathCache cacheWith(long served, long wouldServe, long lookups, int capacity) {
        PathCache cache = new PathCache(capacity);
        PathCache.Counters counters = cache.counters();
        counters.served = served;
        counters.wouldServe = wouldServe;
        counters.lookups = lookups;
        return cache;
    }

    private static String joined(List<String> lines) { return String.join("\n", lines); }

    /**
     * The defect. Five hits, none of them spendable, and the block must not call any of them a
     * skipped search.
     */
    @Test
    void hitsThatSavedNothingAreNotCountedAsSkippedSearches() {
        List<String> lines = CacheStatusLines.render(
            config(PathCacheMode.SERVE, 4096), cacheWith(0L, 5L, 100L, 4096));

        String skipped = lineContaining(lines, "searches skipped");
        assertTrue(skipped.contains("0"), "the measured saving is not zero: " + skipped);
        assertFalse(skipped.contains("5"), "hits that saved nothing were reported as savings: " + skipped);
        assertTrue(joined(lines).contains("hits that saved nothing"),
            "the hypothetical count is not reported at all, so the hits vanish: " + joined(lines));
    }

    /** With both kinds present, neither the sum nor the wrong half may appear on the measured line. */
    @Test
    void theMeasuredAndHypotheticalCountsAreNeverAddedTogether() {
        List<String> lines = CacheStatusLines.render(
            config(PathCacheMode.SERVE, 4096), cacheWith(3L, 5L, 100L, 4096));

        String skipped = lineContaining(lines, "searches skipped");
        assertTrue(skipped.contains("3"), skipped);
        assertFalse(skipped.contains("8"), "served and wouldServe were summed: " + skipped);
        assertTrue(lineContaining(lines, "hits that saved nothing").contains("5"));
    }

    /**
     * The positive control for the two tests above. If the block stopped reporting these rows they
     * would both pass on absence, which is the failure mode this project keeps meeting.
     */
    @Test
    void theBlockReportsBothKindsWithTheirSharesWhenThereAreLookups() {
        List<String> lines = CacheStatusLines.render(
            config(PathCacheMode.SERVE, 4096), cacheWith(3L, 5L, 100L, 4096));

        assertTrue(lineContaining(lines, "searches skipped").contains("3.0%"),
            "the measured share is missing: " + joined(lines));
        assertTrue(lineContaining(lines, "hits that saved nothing").contains("5.0%"),
            "the hypothetical share is missing: " + joined(lines));
    }

    /** No lookups means no share to take, and a percentage of nothing must not be printed. */
    @Test
    void noLookupsPrintsNoShare() {
        List<String> lines = CacheStatusLines.render(
            config(PathCacheMode.SERVE, 4096), cacheWith(0L, 0L, 0L, 4096));
        assertFalse(joined(lines).contains("%"), "a share was computed with no lookups to divide by");
        assertTrue(joined(lines).contains("searches skipped"), "the rows disappeared entirely");
    }

    /** Capacity is what the running cache has, and a saved change says it needs a restart. */
    @Test
    void capacityIsTheOneInForceAndAPendingChangeIsCalledOut() {
        List<String> lines = CacheStatusLines.render(
            config(PathCacheMode.SERVE, 9999), cacheWith(0L, 0L, 0L, 4096));

        assertTrue(lineContaining(lines, "route cache:").contains("/4096"),
            "the saved capacity was printed as if it were in force: " + joined(lines));
        assertTrue(joined(lines).contains("next restart"),
            "a saved capacity nothing is using was not called out: " + joined(lines));
    }

    /** And with no pending change there is no restart notice to distract from the numbers. */
    @Test
    void anUnchangedCapacityPrintsNoRestartNotice() {
        List<String> lines = CacheStatusLines.render(
            config(PathCacheMode.SERVE, 4096), cacheWith(0L, 0L, 0L, 4096));
        assertFalse(joined(lines).contains("next restart"), joined(lines));
    }

    /** Measuring mode says the hits are unspent because of the mode, not because of the entries. */
    @Test
    void measuringModeExplainsWhyTheHitsAreUnspent() {
        List<String> lines = CacheStatusLines.render(
            config(PathCacheMode.SHADOW, 4096), cacheWith(0L, 5L, 100L, 4096));
        assertTrue(joined(lines).contains("switch to SERVE"), joined(lines));
    }

    @Test
    void anInactiveCacheSaysSoAndReportsNothingElse() {
        assertEquals(List.of("  route cache: off"), CacheStatusLines.render(
            config(PathCacheMode.OFF, 4096), cacheWith(3L, 5L, 100L, 4096)));
    }

    private static String lineContaining(List<String> lines, String needle) {
        return lines.stream().filter(line -> line.contains(needle)).findFirst().orElseThrow(
            () -> new AssertionError("no line contains " + needle + " in:\n" + joined(lines)));
    }
}
