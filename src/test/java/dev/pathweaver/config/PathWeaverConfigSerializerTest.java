package dev.pathweaver.config;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import me.shedaniel.autoconfig.serializer.ConfigSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PathWeaverConfigSerializerTest {
    @TempDir Path tempDir;

    @Test void migratesLegacyTrueFalseToEnabled() throws Exception { assertLegacy(true, false, true); }
    @Test void migratesLegacyFalseFalseToDisabled() throws Exception { assertLegacy(false, false, false); }
    @Test void migratesLegacyTrueTrueToDisabled() throws Exception { assertLegacy(true, true, false); }
    @Test void migratesLegacyFalseTrueToDisabled() throws Exception { assertLegacy(false, true, false); }

    @Test void missingLegacyPanicUsesHistoricalFalseDefault() throws Exception {
        assertMigrates("{\"asyncEnabled\":false}", false);
    }

    @Test void missingLegacyAsyncUsesHistoricalTrueDefault() throws Exception {
        assertMigrates("{\"syncFallbackOnly\":true}", false);
    }

    @Test void missingBothLegacyKeysUsesHistoricalEnabledDefaults() throws Exception {
        assertMigrates("{\"poolThreads\":3}", true);
    }

    @Test void v2ReloadIsIdempotent() throws Exception {
        Path path = configPath();
        Files.writeString(path, """
            {"configVersion":2,"enabled":false,"poolThreads":3,"maxInFlight":17}
            """);
        PathWeaverConfigSerializer serializer = new PathWeaverConfigSerializer(path);
        PathWeaverConfig first = serializer.deserialize();
        serializer.serialize(first);
        String once = Files.readString(path);
        PathWeaverConfig second = serializer.deserialize();
        serializer.serialize(second);
        assertEquals(once, Files.readString(path));
        assertFalse(second.enabled);
        assertEquals(2, second.configVersion);
    }

    @Test void migratedSaveRemovesLegacyKeysAndWritesV2() throws Exception {
        Path path = configPath();
        Files.writeString(path, "{\"asyncEnabled\":true,\"syncFallbackOnly\":false}");
        PathWeaverConfigSerializer serializer = new PathWeaverConfigSerializer(path);
        serializer.serialize(serializer.deserialize());
        JsonObject saved = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
        assertEquals(2, saved.get("configVersion").getAsInt());
        assertTrue(saved.get("enabled").getAsBoolean());
        assertFalse(saved.has("asyncEnabled"));
        assertFalse(saved.has("syncFallbackOnly"));
    }

    @Test void migrationPreservesSubordinateValuesThenClampsInvalidNumbers() throws Exception {
        Path path = configPath();
        Files.writeString(path, """
            {"asyncEnabled":true,"syncFallbackOnly":false,
             "allowModdedMobAsync":true,"repathElisionEnabled":false,
             "poolThreads":999,"maxInFlight":0,"repathToleranceBlocks":7,
             "stalenessMoveThreshold":5.5,"maxResultAgeTicks":77}
            """);
        PathWeaverConfig c = new PathWeaverConfigSerializer(path).deserialize();
        assertTrue(c.enabled);
        assertTrue(c.allowModdedMobAsync);
        assertEquals(PathWeaverConfig.MAX_POOL_THREADS, c.poolThreads);
        assertEquals(1, c.maxInFlight);
        assertEquals(7, c.repathToleranceBlocks);
        assertEquals(5.5, c.stalenessMoveThreshold);
        assertEquals(77, c.maxResultAgeTicks);
    }

    @Test void malformedToggleFailsClosedThroughExistingLoadFailureSignal() throws Exception {
        Path path = configPath();
        Files.writeString(path, "{\"asyncEnabled\":\"yes\"}");
        java.util.concurrent.atomic.AtomicBoolean failed = new java.util.concurrent.atomic.AtomicBoolean();
        var tracked = new LoadFailureTrackingSerializer<>(
            new PathWeaverConfigSerializer(path), failed);
        assertThrows(ConfigSerializer.SerializationException.class, tracked::deserialize);
        assertTrue(failed.get());
        PathWeaverConfig previous = PathWeaverConfig.get();
        try {
            PathWeaverConfig.publishLoaded(tracked.createDefault(), failed.get());
            assertFalse(PathWeaverConfig.get().enabled);
        } finally { PathWeaverConfig.set(previous); }
    }

    @Test void futureSchemaFailsClosedRatherThanGuessing() throws Exception {
        Path path = configPath();
        Files.writeString(path, "{\"configVersion\":3,\"enabled\":true}");
        assertThrows(ConfigSerializer.SerializationException.class,
            () -> new PathWeaverConfigSerializer(path).deserialize());
    }

    @Test void ambiguousMixedSchemaFailsClosedWithoutOverwritingSource() throws Exception {
        Path path = configPath();
        String mixed = "{\"configVersion\":2,\"enabled\":true,\"syncFallbackOnly\":true}";
        Files.writeString(path, mixed);
        assertThrows(ConfigSerializer.SerializationException.class,
            () -> new PathWeaverConfigSerializer(path).deserialize());
        assertEquals(mixed, Files.readString(path));
    }

    @Test void malformedSubordinateCannotBeCoercedIntoUnsafeEnablement() throws Exception {
        Path path = configPath();
        String malformed = "{\"configVersion\":2,\"enabled\":true,"
            + "\"allowModdedMobAsync\":\"true\"}";
        Files.writeString(path, malformed);
        assertThrows(ConfigSerializer.SerializationException.class,
            () -> new PathWeaverConfigSerializer(path).deserialize());
        assertEquals(malformed, Files.readString(path));
    }

    @Test void retiredOverrideTrueBecomesTheAllTier() throws Exception {
        assertTier("{\"configVersion\":2,\"enabled\":true,\"overrideCompatibilityScan\":true}",
            CompatibilityTier.UNSAFE);
    }

    /**
     * {@code overrideCompatibilityScan=false} is the one stored value that is an explicit refusal of
     * what the shipped default now does, so it must resolve to a literal and never to the default.
     *
     * <p>This test used to assert against {@code new PathWeaverConfig().compatibilityTier} — the same
     * expression the production code used — so it compared the code to itself and could not fail for
     * any default. It passed unchanged through the 0.5.0 flip while certifying that a config saying
     * "do not bypass the compatibility scan" now migrates to the tier that bypasses everything.
     * That is why every expectation in {@link #everyLegacyConfigShapeResolvesToALiteralTier()} is a
     * literal.
     */
    @Test void retiredOverrideFalseBecomesAudited() throws Exception {
        assertTier("{\"configVersion\":2,\"enabled\":true,\"overrideCompatibilityScan\":false}",
            CompatibilityTier.AUDITED);
    }

    /** An operator who already chose a tier must not have it rewritten by a stale legacy key. */
    @Test void anExplicitTierWinsOverTheRetiredOverride() throws Exception {
        assertTier("{\"configVersion\":2,\"enabled\":true,\"overrideCompatibilityScan\":true,"
            + "\"compatibilityTier\":\"AUDITED\"}", CompatibilityTier.AUDITED);
    }

    @Test void absentTierTakesTheShippedDefault() throws Exception {
        // Literal, not new PathWeaverConfig().compatibilityTier. Asserting against the same
        // expression the production code uses is what let the overrideCompatibilityScan bug ship,
        // and this sibling still had the shape after that one was fixed.
        assertTier("{\"configVersion\":2,\"enabled\":true}", CompatibilityTier.UNSAFE);
    }

    /**
     * The upgrade hazard created by shipping {@code UNSAFE} as the default.
     *
     * <p>This test used to assert the opposite property — that an absent tier could never resolve to
     * the tier that disables every check — which was true while the default was {@code AUDITED} and
     * is deliberately no longer true. Deleting it outright would have dropped the part that still
     * matters, and it matters more now than it did: an operator who read the warnings and chose
     * checking must keep it. If a default change ever rewrites an explicit tier, this is the failure
     * nobody would notice, because a widened install looks exactly like a working one.
     */
    @Test void anExplicitAuditedTierSurvivesTheDefaultBeingWider() throws Exception {
        assertNotSame(CompatibilityTier.AUDITED, new PathWeaverConfig().compatibilityTier,
            "this test is only meaningful while the shipped default is wider than AUDITED");
        assertTier("{\"configVersion\":2,\"enabled\":true,\"compatibilityTier\":\"AUDITED\"}",
            CompatibilityTier.AUDITED);
    }

    /**
     * Every config shape the serializer accepts, each pinned to a literal tier.
     *
     * <p>The invariant is not "an explicit AUDITED survives" — that is one instance of it. It is
     * that <em>no</em> migration path may resolve through the shipped default, because a default is
     * exactly the thing that changes underneath a migration written years earlier. Two separate
     * defects shipped in 0.5.0 for want of this table: a migration that deferred to the default, and
     * a test that asserted the default against itself. Both were invisible precisely because they
     * agreed with each other.
     *
     * <p>Adding a legacy shape here is cheap. Discovering one was mis-migrated is not: the operator
     * cannot see it, because a widened install behaves exactly like a working one.
     */
    @Test void everyLegacyConfigShapeResolvesToALiteralTier() throws Exception {
        record Shape(String json, CompatibilityTier expected, String why) {}
        List<Shape> shapes = List.of(
            new Shape("{\"configVersion\":2,\"enabled\":true,\"overrideCompatibilityScan\":true}",
                CompatibilityTier.UNSAFE, "an explicit bypass stays a bypass"),
            new Shape("{\"configVersion\":2,\"enabled\":true,\"overrideCompatibilityScan\":false}",
                CompatibilityTier.AUDITED, "an explicit refusal to bypass must never become a bypass"),
            new Shape("{\"configVersion\":2,\"enabled\":true,\"overrideCompatibilityScan\":true,"
                + "\"compatibilityTier\":\"AUDITED\"}",
                CompatibilityTier.AUDITED, "an explicit tier outranks the retired key"),
            new Shape("{\"configVersion\":2,\"enabled\":true,\"compatibilityTier\":\"ALL\"}",
                CompatibilityTier.UNSAFE, "ALL was renamed, not retired"),
            new Shape("{\"configVersion\":2,\"enabled\":true,\"compatibilityTier\":\"STRICT\"}",
                CompatibilityTier.AUDITED, "STRICT was inert on every real pack; AUDITED is the "
                + "nearest tier that still checks"),
            new Shape("{\"configVersion\":2,\"enabled\":true,\"compatibilityTier\":\"UNSAFE\"}",
                CompatibilityTier.UNSAFE, "an explicit UNSAFE is kept"),
            new Shape("{\"configVersion\":2,\"enabled\":true}",
                CompatibilityTier.UNSAFE, "an absent tier takes the shipped default, stated as a literal"));

        for (Shape shape : shapes) {
            assertTier(shape.json(), shape.expected());
        }
    }

    /**
     * The tier was renamed because "ALL" claimed a completeness it never had. Rejecting the old
     * spelling would fail closed on upgrade, switching the mod off for the operators who had opted
     * furthest in -- the opposite of what they asked for.
     */
    @Test void theRetiredAllSpellingMigratesToUnsafeRatherThanFailingClosed() throws Exception {
        assertTier("{\"configVersion\":2,\"enabled\":true,\"compatibilityTier\":\"ALL\"}",
            CompatibilityTier.UNSAFE);
    }

    /** An unreadable tier must fail closed, not silently fall back to a permissive value. */
    @Test void unknownTierFailsClosedInsteadOfGuessing() throws Exception {
        Path path = configPath();
        Files.writeString(path,
            "{\"configVersion\":2,\"enabled\":true,\"compatibilityTier\":\"EVERYTHING\"}");
        assertThrows(Exception.class, () -> new PathWeaverConfigSerializer(path).deserialize());
    }

    @Test void migratedTierSurvivesASaveReloadRoundTrip() throws Exception {
        Path path = configPath();
        Files.writeString(path,
            "{\"configVersion\":2,\"enabled\":true,\"overrideCompatibilityScan\":true}");
        PathWeaverConfigSerializer serializer = new PathWeaverConfigSerializer(path);
        PathWeaverConfig migrated = serializer.deserialize();
        serializer.serialize(migrated);
        String saved = Files.readString(path);
        assertFalse(saved.contains("overrideCompatibilityScan"), saved);
        assertEquals(CompatibilityTier.UNSAFE,
            new PathWeaverConfigSerializer(path).deserialize().compatibilityTier);
    }

    private void assertTier(String json, CompatibilityTier expected) throws Exception {
        Path path = configPath();
        Files.writeString(path, json);
        assertEquals(expected, new PathWeaverConfigSerializer(path).deserialize().compatibilityTier,
            json);
    }

    private void assertLegacy(boolean async, boolean panic, boolean expected) throws Exception {
        assertMigrates("{\"asyncEnabled\":" + async + ",\"syncFallbackOnly\":" + panic + "}", expected);
    }

    private void assertMigrates(String json, boolean expected) throws Exception {
        Path path = configPath();
        Files.writeString(path, json);
        PathWeaverConfig c = new PathWeaverConfigSerializer(path).deserialize();
        assertEquals(expected, c.enabled, json);
        assertEquals(2, c.configVersion, json);
    }

    private Path configPath() throws Exception {
        Files.createDirectories(tempDir);
        return tempDir.resolve("pathweaver.json");
    }

    /**
     * trustedMods gets the same type check as every other persisted field.
     *
     * <p>It was the only one without one. {@code "trustedMods": null} passed every guard, Gson
     * overwrote the initialised list with null, and the null reached the config -- the scanner
     * null-guards it, but Cloth's list entry builder does not, so opening the settings screen threw
     * and ModMenu bounced the user back with no message.
     */
    @Test
    void aNullTrustedModsListIsRejectedLikeAnyOtherWrongType() throws Exception {
        Path path = configPath();
        Files.writeString(path, """
            {"configVersion":2,"enabled":true,"trustedMods":null}
            """);
        PathWeaverConfigSerializer serializer = new PathWeaverConfigSerializer(path);
        assertThrows(Exception.class, serializer::deserialize,
            "an explicit null must fail closed, not become a null field the GUI trips over");
    }

    @Test
    void aTrustedModsListOfNonStringsIsRejected() throws Exception {
        Path path = configPath();
        Files.writeString(path, """
            {"configVersion":2,"enabled":true,"trustedMods":[1,2]}
            """);
        PathWeaverConfigSerializer serializer = new PathWeaverConfigSerializer(path);
        assertThrows(Exception.class, serializer::deserialize);
    }
}
