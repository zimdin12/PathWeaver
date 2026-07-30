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
    @Test void defaultsEnableMasterWithConservativeFallbacks() {
        PathWeaverConfig c = new PathWeaverConfig();
        assertEquals(2, c.configVersion);
        assertTrue(c.enabled);
        assertTrue(c.repathElisionEnabled);
        assertFalse(c.allowModdedMobAsync);
        assertEquals(0, c.repathToleranceBlocks);
        assertEquals(40, c.maxResultAgeTicks);
    }
    @Test void failedLoadSignalOverridesClothDefaultWithSynchronousFailClosedRuntime() {
        PathWeaverConfig clothDefault = new PathWeaverConfig();
        assertTrue(clothDefault.enabled);

        PathWeaverConfig.publishLoaded(clothDefault, true);

        assertFalse(PathWeaverConfig.get().enabled);
    }

    @Test void configRegistrationFailureInstallsSynchronousFailClosedDefaults() {
        PathWeaverConfig previous = PathWeaverConfig.get();
        try {
            PathWeaverConfig.installFailClosedDefaults();
            assertFalse(PathWeaverConfig.get().enabled);
        } finally {
            PathWeaverConfig.set(previous);
        }
    }
    @Test void persistedV2FalseOverridesDefaultOnInitializer() {
        PathWeaverConfig c = new Gson().fromJson(
            "{\"configVersion\":2,\"enabled\":false}", PathWeaverConfig.class);

        c.validatePostLoad();

        assertFalse(c.enabled);
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
    @Test void tierAccessorsReportTheFrozenPolicyAndIgnoreThePersistedField() {
        // The tier is frozen at scan time, so writing the field -- which is what a settings save
        // does -- must not change runtime policy. Before any scan publishes, everything denies.
        PathWeaverConfig c = new PathWeaverConfig();
        assertSame(CompatibilityTier.STRICT, c.compatibilityTier);
        assertFalse(c.allowModdedMobAsync);
        assertEquals(c.bypassesCompatibilityScan(), c.moddedMobAsyncAllowed(),
            "with the flag off, modded mobs follow the frozen tier and nothing else");

        // Invariant rather than a fixed expectation: whatever the frozen policy says, writing the
        // field cannot change it. Asserting a literal false here would couple this test to whether
        // some other test had already published a policy into the same JVM.
        boolean bypass = c.bypassesCompatibilityScan();
        boolean audited = c.allowsAuditedCompatibility();
        for (CompatibilityTier tier : CompatibilityTier.values()) {
            c.compatibilityTier = tier;
            assertEquals(bypass, c.bypassesCompatibilityScan(),
                "writing the field must not change policy: " + tier);
            assertEquals(audited, c.allowsAuditedCompatibility(),
                "writing the field must not change policy: " + tier);
        }

        // The dedicated flag is not tier-derived and stays live.
        c.allowModdedMobAsync = true;
        assertTrue(c.moddedMobAsyncAllowed());
    }
    @Test void moddedMobBypassNeverNamesTheTierEnumInItsSignature() throws Exception {
        // The only caller is a mixin applied during early transformation. Returning the enum, or
        // taking it as a parameter, would force it -- and the Cloth GUI interface it implements --
        // to resolve at that moment. Keep the accessor primitive.
        assertSame(boolean.class,
            PathWeaverConfig.class.getMethod("moddedMobAsyncAllowed").getReturnType());
        assertEquals(0,
            PathWeaverConfig.class.getMethod("moddedMobAsyncAllowed").getParameterCount());
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
