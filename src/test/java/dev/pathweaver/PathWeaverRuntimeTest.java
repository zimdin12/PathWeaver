package dev.pathweaver;

import dev.pathweaver.async.NavigationIdentity;
import dev.pathweaver.async.RequestKey;
import dev.pathweaver.async.RequestTarget;
import dev.pathweaver.async.RequestOrigin;
import dev.pathweaver.duck.PWNavigation;
import net.minecraft.world.level.pathfinder.Path;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PathWeaverRuntimeTest {
    /**
     * A pathological {@code maxInFlight} produces no error and no TPS drop, so nothing surfaced it.
     * Measured on a 371-mod pack, as a share of dispatches not installed within the capture window:
     * 13.5% at 256, 90.7% at 1024, and nothing installed at all while observing at 4096.
     */
    @Test void warnsOnceWhenAlmostNoSearchResultIsUsed() {
        PathWeaverRuntime runtime = PathWeaverRuntime.get();
        runtime.resetWasteReportingForTests();
        try {
            int interval = PathWeaverRuntime.WASTE_SAMPLE_INTERVAL_TICKS;

            // A window that has not elapsed yet must not be judged, however bad it looks.
            for (int i = 0; i < PathWeaverRuntime.WASTE_MIN_SAMPLE * 2; i++) runtime.markDispatched();
            runtime.reportIfMostResultsAreWasted(interval - 1);
            assertFalse(runtime.wasteReported(), "sampled before the window elapsed");

            runtime.reportIfMostResultsAreWasted(interval);
            assertFalse(runtime.wasteReported(),
                "one bad window may just be a burst straddling the sampling boundary");

            for (int i = 0; i < PathWeaverRuntime.WASTE_MIN_SAMPLE * 2; i++) runtime.markDispatched();
            runtime.reportIfMostResultsAreWasted(interval * 2L);
            assertTrue(runtime.wasteReported(),
                "two consecutive wasted windows is the footgun this exists for");

            // Once per server session; a wasted pool must not spam the log every minute.
            for (int i = 0; i < PathWeaverRuntime.WASTE_MIN_SAMPLE * 2; i++) runtime.markDispatched();
            runtime.reportIfMostResultsAreWasted(interval * 3L);
            assertTrue(runtime.wasteReported(), "the flag latches until the next server session");
        } finally {
            runtime.resetWasteReportingForTests();
        }
    }

    @Test void staysQuietWhenResultsAreBeingUsedOrTheSampleIsTooSmall() {
        PathWeaverRuntime runtime = PathWeaverRuntime.get();
        runtime.resetWasteReportingForTests();
        try {
            int interval = PathWeaverRuntime.WASTE_SAMPLE_INTERVAL_TICKS;

            // Too few searches to conclude anything: a couple of superseded repaths is normal.
            for (int i = 0; i < PathWeaverRuntime.WASTE_MIN_SAMPLE - 1; i++) runtime.markDispatched();
            runtime.reportIfMostResultsAreWasted(interval);
            assertFalse(runtime.wasteReported(), "a quiet window must not accuse the configuration");

            // A healthy ratio must stay silent even at high volume. The shipped default measured
            // ~86% installed within the capture window and must never trip this.
            runtime.resetWasteReportingForTests();
            for (int i = 0; i < 1000; i++) {
                runtime.markDispatched();
                if (i % 10 != 0) runtime.markOutcome(dev.pathweaver.async.RequestOutcome.INSTALLED);       // 90% installed
            }
            runtime.reportIfMostResultsAreWasted(interval);
            assertFalse(runtime.wasteReported(), "90% installed is healthy, not a footgun");

            // A healthy window must reset the streak: one bad window either side of a good one is
            // not two consecutive bad windows.
            runtime.resetWasteReportingForTests();
            for (int i = 0; i < PathWeaverRuntime.WASTE_MIN_SAMPLE * 2; i++) runtime.markDispatched();
            runtime.reportIfMostResultsAreWasted(interval);            // bad
            for (int i = 0; i < 1000; i++) { runtime.markDispatched(); runtime.markOutcome(dev.pathweaver.async.RequestOutcome.INSTALLED); }
            runtime.reportIfMostResultsAreWasted(interval * 2L);       // good -> streak resets
            for (int i = 0; i < PathWeaverRuntime.WASTE_MIN_SAMPLE * 2; i++) runtime.markDispatched();
            runtime.reportIfMostResultsAreWasted(interval * 3L);       // bad again, but only one
            assertFalse(runtime.wasteReported(), "a good window must reset the streak");

            // A *quiet* window must break the run too. This is the straddle the sampler exists to
            // tolerate: a burst dispatches in window one, window two is too quiet to judge and
            // absorbs the late installs, and an unrelated burst arrives later. Those three are not
            // two consecutive bad windows, and treating them as such is exactly the false positive
            // the two-window rule was added to prevent.
            runtime.resetWasteReportingForTests();
            for (int i = 0; i < PathWeaverRuntime.WASTE_MIN_SAMPLE * 2; i++) runtime.markDispatched();
            runtime.reportIfMostResultsAreWasted(interval);            // bad
            for (int i = 0; i < PathWeaverRuntime.WASTE_MIN_SAMPLE - 1; i++) runtime.markOutcome(dev.pathweaver.async.RequestOutcome.INSTALLED);
            runtime.reportIfMostResultsAreWasted(interval * 2L);       // too quiet to judge
            for (int i = 0; i < PathWeaverRuntime.WASTE_MIN_SAMPLE * 2; i++) runtime.markDispatched();
            runtime.reportIfMostResultsAreWasted(interval * 3L);       // bad, but not consecutive
            assertFalse(runtime.wasteReported(),
                "a window too quiet to judge must break the run, not preserve it");
        } finally {
            runtime.resetWasteReportingForTests();
        }
    }

    private static final class FakeNavigation implements PWNavigation {
        double lastMovementSpeed = Double.NaN;
        @Override public void pathweaver$beginMovementRequest(double speed) {
            lastMovementSpeed = speed;
        }
        int movementDepth = 0;
        @Override public void pathweaver$enterMovementRequest() { movementDepth++; }
        @Override public void pathweaver$exitMovementRequest() { movementDepth--; }
        @Override public boolean pathweaver$consumeAcceptedDeferred() { return false; }

        @Override public void pathweaver$rearmRecompute() { }
        @Override public void pathweaver$rollbackOptimisticTarget() { }
        @Override public void pathweaver$abortFailedInstall() { }

        int dones;
        private final Object world = new Object();
        public boolean pathweaver$install(Path path) { return true; }
        public boolean pathweaver$stale(double x, double y, double z) { return false; }
        public NavigationIdentity pathweaver$identity() {
            return new NavigationIdentity("uuid", world, "dimension", this, null, 1L);
        }
    }

    @Test void startStopAdvancesEpochAndTokensNeverRepeat() {
        PathWeaverRuntime runtime = PathWeaverRuntime.get();
        runtime.onServerStopping(null);
        try {
            runtime.onServerStarting(null);
            long firstEpoch = runtime.currentServerEpoch();
            RequestKey first = runtime.nextRequestKey(42);
            RequestKey second = runtime.nextRequestKey(42);
            assertEquals(firstEpoch, first.serverEpoch());
            assertEquals(firstEpoch, second.serverEpoch());
            assertNotEquals(first.requestToken(), second.requestToken());

            runtime.onServerStopping(null);
            assertTrue(runtime.currentServerEpoch() > firstEpoch);
            assertThrows(IllegalStateException.class, () -> runtime.nextRequestKey(42));

            runtime.onServerStarting(null);
            RequestKey restarted = runtime.nextRequestKey(42);
            assertTrue(restarted.serverEpoch() > firstEpoch);
            assertNotEquals(second.requestToken(), restarted.requestToken());
        } finally {
            runtime.onServerStopping(null);
        }
    }

    @Test void serverStopBalancesEveryAcceptedRegistrationBeforeClearing() {
        PathWeaverRuntime runtime = PathWeaverRuntime.get();
        runtime.onServerStopping(null);
        try {
            runtime.onServerStarting(null);
            FakeNavigation navigation = new FakeNavigation();
            RequestKey key = runtime.nextRequestKey(77);
            runtime.entitySink().register(key, navigation,
                RequestTarget.of(Set.of(), 0, false, 0, 0.0F), false, RequestOrigin.MOVE_TO);

            runtime.onServerStopping(null);

            assertEquals(0, runtime.entitySink().inFlightCount());
        } finally {
            runtime.onServerStopping(null);
        }
    }

    /**
     * The setup-failure log flag must re-arm per server session, and nothing checked that it did.
     *
     * <p>Deleting {@code setupFailureLogged.set(false)} from {@code onServerStarting} survived the
     * whole suite — reinstating exactly the bug its javadoc says it fixes: the flag is per-process
     * while the counter it refers to is per-session, so loading a second world showed a climbing
     * "dispatch setup failed" count with nothing in the log, after a message that had promised the
     * counter would keep counting.
     */
    @org.junit.jupiter.api.Test
    void theSetupFailureWarningReArmsForEachServerSession() {
        PathWeaverRuntime runtime = PathWeaverRuntime.get();
        assertTrue(runtime.claimSetupFailureLog(),
            "precondition: the first claim of a session must succeed");
        assertFalse(runtime.claimSetupFailureLog(),
            "and the second must not -- one warning per session, not per mob per tick");

        runtime.onServerStarting(null);
        assertTrue(runtime.claimSetupFailureLog(),
            "a new server session must re-arm the warning, or the counter climbs with nothing in "
                + "the log to explain it");
    }
}
