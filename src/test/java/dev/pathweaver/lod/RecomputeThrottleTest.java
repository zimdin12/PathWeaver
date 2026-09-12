package dev.pathweaver.lod;

import dev.pathweaver.config.PathWeaverConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The only rule in this mod that decides a mob should NOT get what vanilla would have given it.
 *
 * <p>Every test here names the failure it catches. Three of them exist because the obvious
 * implementation gets them wrong: a throttle that fails closed silences mobs when its input is
 * missing, a throttle that compares ticks with subtraction blocks every recompute forever after a
 * rollback, and a throttle that treats "never recomputed" as "recomputed at tick zero" delays the
 * first correction of a route computed before the mob moved.
 */
class RecomputeThrottleTest {

    private static final long NEVER = Long.MIN_VALUE;

    private static PathWeaverConfig on(int distance, int interval) {
        PathWeaverConfig config = new PathWeaverConfig();
        config.lodEnabled = true;
        config.lodMinDistanceBlocks = distance;
        config.lodIntervalTicks = interval;
        return config;
    }

    private static double blocks(double n) {
        return n * n;
    }

    // ------------------------------------------------------------------ the switch and its default

    /** Catches: LOD silently on. The mod's headline claim is that it does not change mob behaviour. */
    @Test
    void aFreshConfigDoesNotThrottleAnything() {
        PathWeaverConfig shipped = new PathWeaverConfig();
        assertFalse(shipped.lodEnabled, "LOD ships enabled, which changes mob behaviour by default");
        assertTrue(RecomputeThrottle.allows(shipped, blocks(5000), 1000L, 999L),
            "the shipped configuration throttles a recompute");
    }

    /** Catches: an inverted switch, which would throttle only when the feature is off. */
    @Test
    void withTheFeatureOffEvenAVeryDistantMobRecomputesEveryTick() {
        PathWeaverConfig off = on(64, 10);
        off.lodEnabled = false;
        assertTrue(RecomputeThrottle.allows(off, blocks(5000), 1000L, 1000L),
            "a disabled LOD still throttled");
    }

    // --------------------------------------------------------------------------- the distance rule

    /** Catches: throttling mobs the player is standing next to. */
    @Test
    void aMobInsideTheDistanceIsNeverThrottled() {
        assertTrue(RecomputeThrottle.allows(on(64, 10), blocks(63), 1000L, 1000L),
            "a mob 63 blocks away was throttled with a 64 block threshold");
    }

    /**
     * Catches: an off-by-one at the threshold, in the direction that matters.
     *
     * <p>At exactly the configured distance the mob is NOT yet beyond it, so it must still recompute.
     * The comparison is on squared distance, where an off-by-one is easy to write and invisible.
     */
    @Test
    void atExactlyTheThresholdTheMobStillRecomputes() {
        assertTrue(RecomputeThrottle.allows(on(64, 10), blocks(64), 1000L, 1000L),
            "a mob at exactly the threshold distance was throttled");
    }

    /** Catches: the distance check not working at all. */
    @Test
    void beyondTheDistanceAndInsideTheIntervalTheRecomputeIsRefused() {
        assertFalse(RecomputeThrottle.allows(on(64, 10), blocks(65), 1000L, 995L),
            "a distant mob that recomputed 5 ticks ago was allowed to recompute again");
    }

    // --------------------------------------------------------------------------- the interval rule

    /** Catches: an interval of N meaning every N+1 ticks. */
    @Test
    void atExactlyTheIntervalTheRecomputeIsAllowed() {
        assertTrue(RecomputeThrottle.allows(on(64, 10), blocks(500), 1010L, 1000L),
            "a mob at exactly the configured interval was still throttled");
    }

    @Test
    void oneTickShortOfTheIntervalIsRefused() {
        assertFalse(RecomputeThrottle.allows(on(64, 10), blocks(500), 1009L, 1000L),
            "a mob one tick short of the interval was allowed through");
    }

    // ------------------------------------------------------------------------------ the edge cases

    /**
     * Catches: a first path delayed.
     *
     * <p>A navigation that has never recomputed is following a route derived before it set off. That
     * is the correction most worth having, and a throttle that treats "never" as a very old tick
     * would happen to allow it, while one that treats it as tick zero on a fresh world would not.
     */
    @Test
    void aNavigationThatHasNeverRecomputedIsAllowedThrough() {
        assertTrue(RecomputeThrottle.allows(on(64, 10), blocks(500), 5L, NEVER),
            "a mob's first recompute was throttled");
    }

    /**
     * Catches: the rollback lockout.
     *
     * <p>A world reload or a rollback moves the tick counter backwards. Plain subtraction then gives
     * a negative age, which is less than any interval, so every recompute is refused until the clock
     * passes where it was before. On a long-running world that is minutes of mobs not correcting
     * their routes, from one setting nobody touched. The result cache had this same bug shape and it
     * is fixed there too.
     */
    @Test
    void afterTheClockMovesBackwardsRecomputesAreNotLockedOut() {
        assertTrue(RecomputeThrottle.allows(on(64, 10), blocks(500), 50L, 100_000L),
            "time moving backwards locked out every recompute");
    }

    /**
     * Catches: failing closed.
     *
     * <p>Every other guard in this mod refuses when its input is missing, because the action guarded
     * is ours and skipping it costs a saving. This one guards vanilla's action, so a missing config
     * must mean "let the game behave normally" rather than "throttle on a guess".
     */
    @Test
    void withNoConfigAtAllTheRecomputeIsAllowed() {
        assertTrue(RecomputeThrottle.allows(null, blocks(5000), 1000L, 1000L),
            "a missing config caused a mob's recompute to be suppressed");
    }

    /**
     * Catches: a world with no players throttling everything forever.
     *
     * <p>The caller passes MAX_VALUE when there is no player to measure against. That is beyond any
     * threshold, so those mobs are throttled, which is correct: nobody can see them. This test exists
     * to record that as an intended outcome rather than an accident of the sentinel value.
     */
    @Test
    void withNoPlayersInTheLevelDistantMobsAreThrottled() {
        assertFalse(RecomputeThrottle.allows(on(64, 10), Double.MAX_VALUE, 1000L, 999L),
            "a level with no players did not throttle, so the sentinel is not being compared");
    }

    /**
     * Catches: the distance scan being paid for by servers that never turned LOD on.
     *
     * <p>Finding the nearest player is a scan over the level's players. The hook passes it as a
     * supplier precisely so that with the feature off it is never evaluated, and that is a property
     * worth a test rather than a comment: it is invisible in behaviour and only shows up as tick time
     * on somebody else's server.
     */
    @Test
    void withTheFeatureOffTheDistanceIsNeverEvenComputed() {
        PathWeaverConfig off = new PathWeaverConfig();
        int[] calls = {0};
        boolean allowed = RecomputeThrottle.allows(off, () -> { calls[0]++; return 0.0; }, 1000L, 1L);
        assertTrue(allowed, "a disabled LOD refused a recompute");
        assertEquals(0, calls[0],
            "the nearest-player scan ran with LOD switched off, which is a cost for no benefit");
    }

    /** The same, for a missing config: nothing is read and nothing is scanned. */
    @Test
    void withNoConfigTheDistanceIsNeverComputedEither() {
        int[] calls = {0};
        assertTrue(RecomputeThrottle.allows(null, () -> { calls[0]++; return 0.0; }, 1000L, 1L));
        assertEquals(0, calls[0], "a missing config still triggered the nearest-player scan");
    }

    /** And the positive control: with LOD on, the distance IS consulted exactly once. */
    @Test
    void withTheFeatureOnTheDistanceIsConsultedOnce() {
        int[] calls = {0};
        RecomputeThrottle.allows(on(64, 10), () -> { calls[0]++; return blocks(500); }, 1000L, 999L);
        assertEquals(1, calls[0], "the distance was not consulted exactly once with LOD enabled");
    }

    /** Catches: clamping that lets a zero distance through and throttles the whole world. */
    @Test
    void aZeroDistanceIsClampedAwayByValidation() {
        PathWeaverConfig config = on(0, 0);
        config.validatePostLoad();
        assertTrue(config.lodMinDistanceBlocks >= 16,
            "a zero LOD distance survived validation and would throttle mobs underfoot");
        assertTrue(config.lodIntervalTicks >= 2,
            "a zero LOD interval survived validation and would throttle nothing while looking on");
    }
}
