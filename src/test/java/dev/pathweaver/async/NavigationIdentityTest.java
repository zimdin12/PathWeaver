package dev.pathweaver.async;

import dev.pathweaver.duck.PWNavigation;
import net.minecraft.world.level.pathfinder.Path;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class NavigationIdentityTest {
    @Test void stableValuesAndLiveObjectIdentitiesUseDifferentComparisonSemantics() {
        FakeNav nav = new FakeNav();
        UUID uuid = UUID.randomUUID();
        Object world = new Object();
        String dimension = new String("overworld");
        Object path = new Object();
        NavigationIdentity captured = new NavigationIdentity(
            uuid, world, dimension, nav, path, 7L);

        assertTrue(captured.sameLiveIdentity(new NavigationIdentity(
            UUID.fromString(uuid.toString()), world, new String("overworld"), nav, path, 7L)));
        assertFalse(captured.sameLiveIdentity(new NavigationIdentity(
            uuid, new Object(), dimension, nav, path, 7L)));
        assertFalse(captured.sameLiveIdentity(new NavigationIdentity(
            uuid, world, dimension, new FakeNav(), path, 7L)));
        assertFalse(captured.sameLiveIdentity(new NavigationIdentity(
            uuid, world, dimension, nav, new Object(), 7L)));
        assertFalse(captured.sameLiveIdentity(new NavigationIdentity(
            uuid, world, dimension, nav, path, 8L)));
    }

    private static final class FakeNav implements PWNavigation {
        public void pathweaver$install(Path path) { }
        public boolean pathweaver$stale(double x, double y, double z) { return false; }
        public NavigationIdentity pathweaver$identity() { throw new UnsupportedOperationException(); }
        public void pathweaver$onPathfindingDone() { }
    }
}
