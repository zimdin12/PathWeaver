package dev.pathweaver.config;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.Test;

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
 *
 * <p>This is also where an unsupported field type is caught. The serializer refuses to load a config
 * whose declared type it has no check for, which is the right runtime behaviour and a poor way to
 * find out: it would surface as an operator's settings file failing to load. Because every field here
 * is loaded with its own default, adding a field of an unhandled type turns this test red on the
 * ordinary build instead. That is detection at build time, not exhaustiveness: the compiler checks
 * nothing here, this test does.
 */
class StrictFieldTypeCoverageTest {


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
            // Static is not persisted, and neither is transient: Gson skips it, so it never reaches
            // the file. Same rule the serializer walks by, derived from the modifier on both sides.
            if (Modifier.isStatic(field.getModifiers())
                || Modifier.isTransient(field.getModifiers())) {
                continue;
            }
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

            Exception refused = assertThrows(Exception.class,
                () -> load(withField(name, wrongTypeFor(field))),
                name + " accepted a value of the wrong type");
            assertTrue(because(refused).contains(name),
                name + " was refused for some other reason than its own type check: "
                    + because(refused));
        }

        assertTrue(visited.size() >= 14,
            "the field walk found only " + visited.size() + " settings: " + visited);
        assertTrue(visited.containsAll(REVIEW_FINDING),
            "the settings the review named are not covered: " + visited);
    }

    /**
     * A wrong-typed value that Gson would quietly accept, chosen from the declared type.
     *
     * <p>This is the part the first version got wrong. It fed strings like "not a int", which Gson
     * rejects on its own, so the test passed against a serializer with no strict checks at all and
     * proved nothing about them. The discriminating input is the one that coercion RESCUES:
     *
     * <ul>
     *   <li>a quoted number for anything numeric, and a quoted boolean, which Gson converts;</li>
     *   <li>an unknown constant name for an enum, which Gson turns into null rather than failing,
     *       after which the post-load defaults quietly substitute a value the operator did not
     *       choose;</li>
     *   <li>an explicit null for a list, which Gson writes straight over the initialised one. That
     *       is the edit that reached the settings screen and threw.</li>
     * </ul>
     */
    private static JsonElement wrongTypeFor(Field field) {
        Class<?> type = field.getType();
        if (List.class.isAssignableFrom(type)) return JsonNull.INSTANCE;
        if (type.isEnum()) return new JsonPrimitive("NOT_A_REAL_CONSTANT");
        if (type == boolean.class) return new JsonPrimitive("true");
        if (type == double.class || type == float.class) return new JsonPrimitive("4.0");
        return new JsonPrimitive("7");
    }

    /** The whole cause chain, because the serializer wraps its own IllegalArgumentException. */
    private static String because(Throwable failure) {
        StringBuilder text = new StringBuilder();
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            text.append(cause.getMessage()).append(" | ");
        }
        return text.toString();
    }

    private static JsonObject withField(String name, JsonElement value) {
        JsonObject raw = new JsonObject();
        raw.addProperty("configVersion", PathWeaverConfig.CURRENT_CONFIG_VERSION);
        raw.addProperty("enabled", true);
        raw.add(name, value);
        return raw;
    }

    /**
     * Writes the config under the build directory rather than a JUnit temp directory.
     *
     * <p>{@code @TempDir} failed with "Failed to create default temp directory" partway through a run
     * that executes this suite eighteen times, and a test that cannot start is indistinguishable from
     * one that failed. The build directory is ours, is cleaned by the build, and does not depend on
     * the state of the machine's temp space.
     */
    private PathWeaverConfig load(JsonObject raw) throws Exception {
        Path directory = Path.of("build", "test-scratch", "strict-field-types");
        Files.createDirectories(directory);
        Path path = directory.resolve("pathweaver.json");
        Files.writeString(path, raw.toString());
        return new PathWeaverConfigSerializer(path).deserialize();
    }
}
