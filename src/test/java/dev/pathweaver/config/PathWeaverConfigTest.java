package dev.pathweaver.config;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PathWeaverConfigTest {
    @Test void autoPoolThreadsClampsToAtLeastOne() {
        PathWeaverConfig c = new PathWeaverConfig();
        c.poolThreads = 0;
        assertTrue(c.resolvedPoolThreads() >= 1);
    }
    @Test void explicitPoolThreadsHonored() {
        PathWeaverConfig c = new PathWeaverConfig();
        c.poolThreads = 3;
        assertEquals(3, c.resolvedPoolThreads());
    }
    @Test void defaultsAreConservative() {
        PathWeaverConfig c = new PathWeaverConfig();
        assertTrue(c.asyncEnabled);
        assertTrue(c.repathElisionEnabled);
        assertFalse(c.distanceThrottleEnabled);
        assertFalse(c.syncFallbackOnly);
    }
}
