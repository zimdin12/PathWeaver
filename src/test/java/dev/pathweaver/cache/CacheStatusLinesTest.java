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

        assertEquals("0", countOn(lines, "searches skipped"),
            "hits that saved nothing were reported as savings: " + joined(lines));
        assertEquals("5", countOn(lines, "hits that saved nothing"),
            "the hypothetical count is not reported, so the hits vanish: " + joined(lines));
    }

    /** With both kinds present, neither the sum nor the wrong half may appear on the measured line. */
    @Test
    void theMeasuredAndHypotheticalCountsAreNeverAddedTogether() {
        List<String> lines = CacheStatusLines.render(
            config(PathCacheMode.SERVE, 4096), cacheWith(3L, 5L, 100L, 4096));

        assertEquals("3", countOn(lines, "searches skipped"),
            "served and wouldServe were summed: " + joined(lines));
        assertEquals("5", countOn(lines, "hits that saved nothing"), joined(lines));
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
        // The counts themselves, so the shares cannot stand in for them. With 100 lookups the share
        // text happens to repeat the count digits, which is exactly why the count is read as a token.
        assertEquals("3", countOn(lines, "searches skipped"));
        assertEquals("5", countOn(lines, "hits that saved nothing"));
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

    /**
     * The hypothetical count is explained as history, not as a total waiting to be claimed.
     *
     * <p>Counters survive a settings change while the entries behind them are cleared, so in SERVE
     * this row can hold hits counted during an earlier measuring period. Telling an operator that
     * switching to SERVE spends "these" is wrong in both modes: the entries are gone and a mode
     * change is not retroactive. What SERVE buys is future hits.
     */
    @Test
    void theHypotheticalCountIsExplainedAsPastHitsRatherThanPendingSavings() {
        String measuring = joined(CacheStatusLines.render(
            config(PathCacheMode.SHADOW, 4096), cacheWith(0L, 5L, 100L, 4096)));
        assertTrue(measuring.contains("SERVE"),
            "measuring mode does not say what would change the number: " + measuring);
        assertTrue(measuring.contains("these are done") || measuring.contains("not a total"),
            "measuring mode still offers the past count as claimable: " + measuring);

        String serving = joined(CacheStatusLines.render(
            config(PathCacheMode.SERVE, 4096), cacheWith(3L, 5L, 100L, 4096)));
        assertTrue(serving.contains("Past hits"),
            "serving mode does not explain the leftover count as history: " + serving);
    }

    @Test
    void anInactiveCacheSaysSoAndReportsNothingElse() {
        assertEquals(List.of("  route cache: off"), CacheStatusLines.render(
            config(PathCacheMode.OFF, 4096), cacheWith(3L, 5L, 100L, 4096)));
    }

    /**
     * The count printed on a row, as a token rather than as a substring of the whole line.
     *
     * <p>Searching the line for the digits finds them in the percentage too, so a block printing
     * hardcoded zeros with correct shares satisfied the old assertions. The count is the number
     * between the colour code and its reset, and nothing else on the row can be mistaken for it.
     */
    private static String countOn(List<String> lines, String needle) {
        String line = lineContaining(lines, needle);
        java.util.regex.Matcher digits =
            java.util.regex.Pattern.compile("\u00a7[ae](\\d+)\u00a7r").matcher(line);
        assertTrue(digits.find(), "no count token on the row: " + line);
        return digits.group(1);
    }

    private static String lineContaining(List<String> lines, String needle) {
        return lines.stream().filter(line -> line.contains(needle)).findFirst().orElseThrow(
            () -> new AssertionError("no line contains " + needle + " in:\n" + joined(lines)));
    }
}
