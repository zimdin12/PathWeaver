package dev.pathweaver.config;

import com.google.gson.Gson;
import net.minecraft.world.InteractionResult;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.*;

class PathWeaverConfigTest {
    @Test void runtimeSingletonIsSafelyPublishedAcrossClientAndServerThreads() throws Exception {
        Field instance = PathWeaverConfig.class.getDeclaredField("INSTANCE");
        assertTrue(Modifier.isVolatile(instance.getModifiers()),
            "GUI saves publish on the client thread while the integrated server reads this singleton");
    }
    @Test void autoPoolThreadsClampsToAtLeastOne() {
        PathWeaverConfig c = new PathWeaverConfig();
        c.poolThreads = 0;
        assertTrue(c.resolvedPoolThreads() >= 1);
    }
    @Test void automaticPoolThreadsHonorsCapOnVeryLargeHost() {
        assertEquals(PathWeaverConfig.MAX_POOL_THREADS,
            PathWeaverConfig.resolvePoolThreads(0, 1024));
    }
    @Test void explicitPoolThreadsHonored() {
        PathWeaverConfig c = new PathWeaverConfig();
        c.poolThreads = 3;
        assertEquals(3, c.resolvedPoolThreads());
    }
    @Test void defaultsEnableAsyncWithConservativeFallbacks() {
        PathWeaverConfig c = new PathWeaverConfig();
        assertTrue(c.asyncEnabled);
        assertTrue(c.repathElisionEnabled);
        assertFalse(c.syncFallbackOnly);
        assertFalse(c.allowModdedMobAsync);
        assertEquals(0, c.repathToleranceBlocks);
        assertEquals(40, c.maxResultAgeTicks);
    }
    @Test void failedLoadSignalOverridesClothDefaultWithSynchronousFailClosedRuntime() {
        PathWeaverConfig clothDefault = new PathWeaverConfig();
        assertTrue(clothDefault.asyncEnabled);

        PathWeaverConfig.publishLoaded(clothDefault, true);

        assertFalse(PathWeaverConfig.get().asyncEnabled);
        assertTrue(PathWeaverConfig.get().syncFallbackOnly);
    }

    @Test void configRegistrationFailureInstallsSynchronousFailClosedDefaults() {
        PathWeaverConfig previous = PathWeaverConfig.get();
        try {
            PathWeaverConfig.installFailClosedDefaults();
            assertFalse(PathWeaverConfig.get().asyncEnabled);
            assertTrue(PathWeaverConfig.get().syncFallbackOnly);
        } finally {
            PathWeaverConfig.set(previous);
        }
    }
    @Test void persistedFalseOverridesDefaultOnInitializer() {
        PathWeaverConfig c = new Gson().fromJson(
            "{\"asyncEnabled\":false,\"syncFallbackOnly\":false}", PathWeaverConfig.class);

        c.validatePostLoad();

        assertFalse(c.asyncEnabled);
        assertFalse(c.syncFallbackOnly);
    }
    @Test void invalidLowAndNonFiniteValuesAreClampedPostLoad() {
        PathWeaverConfig c = new PathWeaverConfig();
        c.poolThreads = -7;
        c.maxInFlight = 0;
        c.repathToleranceBlocks = -4;
        c.maxResultAgeTicks = Integer.MIN_VALUE;
        c.stalenessMoveThreshold = Double.NaN;

        c.validatePostLoad();

        assertEquals(0, c.poolThreads);
        assertEquals(1, c.maxInFlight);
        assertEquals(0, c.repathToleranceBlocks);
        assertEquals(1, c.maxResultAgeTicks);
        assertEquals(0.0, c.stalenessMoveThreshold);
    }
    @Test void extremeValuesAreClampedPostLoad() {
        PathWeaverConfig c = new PathWeaverConfig();
        c.poolThreads = Integer.MAX_VALUE;
        c.maxInFlight = Integer.MAX_VALUE;
        c.repathToleranceBlocks = Integer.MAX_VALUE;
        c.maxResultAgeTicks = Integer.MAX_VALUE;
        c.stalenessMoveThreshold = Double.POSITIVE_INFINITY;

        c.validatePostLoad();

        assertEquals(PathWeaverConfig.MAX_POOL_THREADS, c.poolThreads);
        assertEquals(PathWeaverConfig.MAX_IN_FLIGHT, c.maxInFlight);
        assertEquals(PathWeaverConfig.MAX_REPATH_TOLERANCE_BLOCKS, c.repathToleranceBlocks);
        assertEquals(PathWeaverConfig.MAX_RESULT_AGE_TICKS, c.maxResultAgeTicks);
        assertEquals(PathWeaverConfig.MAX_STALENESS_MOVE_THRESHOLD, c.stalenessMoveThreshold);
    }
    @Test void setNormalizesLoadedConfigBeforePublishingIt() {
        PathWeaverConfig c = new PathWeaverConfig();
        c.poolThreads = -1;
        c.maxInFlight = -1;

        PathWeaverConfig.set(c);

        assertSame(c, PathWeaverConfig.get());
        assertEquals(0, c.poolThreads);
        assertEquals(1, c.maxInFlight);
    }
    @Test void saveListenerNormalizesAndPublishesTheSavedObject() {
        PathWeaverConfig previous = PathWeaverConfig.get();
        PathWeaverConfig saved = new PathWeaverConfig();
        saved.poolThreads = -3;
        saved.maxInFlight = 0;
        try {
            assertEquals(InteractionResult.PASS, PathWeaverConfig.onSave(null, saved));
            assertSame(saved, PathWeaverConfig.get());
            assertEquals(0, saved.poolThreads);
            assertEquals(1, saved.maxInFlight);
        } finally {
            PathWeaverConfig.set(previous);
        }
    }
}
