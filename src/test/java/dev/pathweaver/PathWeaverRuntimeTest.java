package dev.pathweaver;

import dev.pathweaver.async.RequestKey;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PathWeaverRuntimeTest {
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
}
