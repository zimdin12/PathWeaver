package dev.pathweaver.config;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every persisted setting rejects a hand-edited wrong type. Every one, discovered rather than listed.
 *
 * <p>The serializer used to name the fields it checked, and that list was wrong three times in the
 * same way: a field was added, the list was not, and nothing failed. {@code workerFailureLimit} and
 * {@code workerFailureWindowTicks} were unchecked while the identical mistake in {@code maxInFlight}
 * was rejected. {@code "trustedMods": null} passed every guard, Gson overwrote the initialised list
 * with null, and opening the settings screen threw, so ModMenu bounced the user back with no message.
 * The three {@code resultCache} settings shipped in 0.8.0 unchecked, which is what the review found.
 *
 * <p>So this test does not name fields either. It walks the config class, and any field it finds must
 * refuse a wrong type and accept its own default. A new setting is covered by existing.
 *
 * <p>The one thing a walk cannot check is that it walked anything, so the count and the three named
 * settings are asserted directly. A discovery that silently found nothing would otherwise pass.
 */
class StrictFieldTypeCoverageTest {

    @TempDir Path tempDir;

    /**
     * Read before the type check, each by a rule of its own. {@code configVersion} chooses the
     * migration branch; {@code enabled} is required at the current schema and deliberately optional
     * in the legacy one. Both are covered by their own tests in the serializer suite.
     */
    private static final Set<String> READ_ELSEWHERE = Set.of("configVersion", "enabled");

    /** The three the 0.9.0 review named, kept explicit so this file says what it closed. */
    private static final Set<String> REVIEW_FINDING =
        Set.of("resultCacheMode", "resultCacheMaxAgeTicks", "resultCacheMaxEntries");

    @Test
    void everyPersistedSettingRefusesAWrongTypeAndAcceptsItsOwnDefault() throws Exception {
        JsonObject defaults = new Gson().toJsonTree(new PathWeaverConfig()).getAsJsonObject();
        List<String> visited = new ArrayList<>();

        for (Field field : PathWeaverConfig.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) continue;
            String name = field.getName();
            if (READ_ELSEWHERE.contains(name)) continue;
            visited.add(name);

            JsonElement good = defaults.get(name);
            assertNotNull(good, name + " is not written to the file at all; this test is not seeing it");
            // The negative control, per field: the file must load when only the type is right, or the
            // rejection below would prove nothing about the type.
            assertEquals(PathWeaverConfig.CURRENT_CONFIG_VERSION,
                load(withField(name, good)).configVersion,
                name + " with its own default value did not load");

            assertThrows(Exception.class, () -> load(withField(name, wrongTypeFor(field))),
                name + " accepted a value of the wrong type");
        }

        assertTrue(visited.size() >= 14,
            "the field walk found only " + visited.size() + " settings: " + visited);
        assertTrue(visited.containsAll(REVIEW_FINDING),
            "the settings the review named are not covered: " + visited);
    }

    /**
     * A value no coercion should rescue, chosen from the declared type.
     *
     * <p>A string for anything numeric or boolean, because Gson turns {@code "7"} into 7 without
     * complaint. A number for an enum, because that is not a constant name. An explicit null for a
     * list, because that is the edit that reached the settings screen.
     */
    private static JsonElement wrongTypeFor(Field field) {
        Class<?> type = field.getType();
        if (List.class.isAssignableFrom(type)) return JsonNull.INSTANCE;
        if (type.isEnum()) return new JsonPrimitive(1);
        return new JsonPrimitive("not a " + type.getSimpleName());
    }

    private static JsonObject withField(String name, JsonElement value) {
        JsonObject raw = new JsonObject();
        raw.addProperty("configVersion", PathWeaverConfig.CURRENT_CONFIG_VERSION);
        raw.addProperty("enabled", true);
        raw.add(name, value);
        return raw;
    }

    private PathWeaverConfig load(JsonObject raw) throws Exception {
        Path path = tempDir.resolve("pathweaver.json");
        Files.writeString(path, raw.toString());
        return new PathWeaverConfigSerializer(path).deserialize();
    }
}
