package dev.pathweaver;

import dev.pathweaver.async.NavigationIdentity;
import dev.pathweaver.async.RequestKey;
import dev.pathweaver.async.RequestTarget;
import dev.pathweaver.duck.PWNavigation;
import net.minecraft.world.level.pathfinder.Path;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PathWeaverRuntimeTest {
    private static final class FakeNavigation implements PWNavigation {
        int dones;
        private final Object world = new Object();
        public void pathweaver$install(Path path) { }
        public boolean pathweaver$stale(double x, double y, double z) { return false; }
        public NavigationIdentity pathweaver$identity() {
            return new NavigationIdentity("uuid", world, "dimension", this, null, 1L);
        }
        public void pathweaver$onPathfindingDone() { dones++; }
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
                RequestTarget.of(Set.of(), 0, false, 0, 0.0F));

            runtime.onServerStopping(null);

            assertEquals(1, navigation.dones);
            assertEquals(0, runtime.entitySink().inFlightCount());
        } finally {
            runtime.onServerStopping(null);
        }
    }
}
