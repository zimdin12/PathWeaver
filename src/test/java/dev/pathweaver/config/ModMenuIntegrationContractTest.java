package dev.pathweaver.config;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import me.shedaniel.autoconfig.ConfigHolder;
import me.shedaniel.autoconfig.annotation.ConfigEntry;
import me.shedaniel.autoconfig.event.ConfigSerializeEvent;
import me.shedaniel.autoconfig.serializer.ConfigSerializer;
import net.minecraft.world.InteractionResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ModMenuIntegrationContractTest {
    private static final Path RESOURCES = Path.of("src", "main", "resources");
    private static final String ENTRYPOINT = "dev.pathweaver.config.PathWeaverModMenu";

    @Test
    void manifestRegistersExplicitModMenuEntrypoint() throws Exception {
        JsonObject manifest = JsonParser.parseString(
            Files.readString(RESOURCES.resolve("fabric.mod.json"))).getAsJsonObject();

        assertEquals(ENTRYPOINT,
            manifest.getAsJsonObject("entrypoints").getAsJsonArray("modmenu").get(0).getAsString());
    }

    @Test
    void entrypointImplementsModMenuApiAndReturnsScreenFactory() throws Exception {
        Class<?> entrypoint = Class.forName(ENTRYPOINT);
        assertTrue(Arrays.stream(entrypoint.getInterfaces())
            .anyMatch(type -> type.getName().equals("com.terraformersmc.modmenu.api.ModMenuApi")));

        Object instance = entrypoint.getConstructor().newInstance();
        assertNotNull(entrypoint.getMethod("getModConfigScreenFactory").invoke(instance));
    }

    @Test
    void missingAutoConfigRegistrationReturnsParentInsteadOfCrashingModMenu() throws Exception {
        Class<?> entrypoint = Class.forName(ENTRYPOINT);
        Object instance = entrypoint.getConstructor().newInstance();
        Object factory = entrypoint.getMethod("getModConfigScreenFactory").invoke(instance);
        var create = factory.getClass().getMethod("create", net.minecraft.client.gui.screens.Screen.class);
        Object screen = assertDoesNotThrow(() -> create.invoke(factory, new Object[] {null}));
        assertNull(screen, "missing registration must return the supplied parent screen");
    }

    /**
     * Cloth labels an enum option by calling {@code Component.translatable} on the key the constant
     * supplies, so a constant whose key has no language entry renders as the raw key in the settings
     * screen. That exact failure has shipped once already.
     *
     * <p>The enums and their constants are DISCOVERED from the config class rather than listed here.
     * The listed version passed for the whole life of a second enum option it had never heard of,
     * which is the defect this style of test exists to prevent, arriving through the test itself.
     */
    @Test
    void everyEnumOptionConstantHasATranslatedLabel() throws Exception {
        JsonObject lang = JsonParser.parseString(Files.readString(
            RESOURCES.resolve(Path.of("assets", "pathweaver", "lang", "en_us.json"))))
            .getAsJsonObject();
        List<Class<?>> enums = Arrays.stream(PathWeaverConfig.class.getDeclaredFields())
            .filter(field -> !Modifier.isStatic(field.getModifiers()))
            .map(java.lang.reflect.Field::getType)
            .filter(Class::isEnum)
            .distinct()
            .toList();
        // Positive control on the discovery itself: a zero here would pass every assertion below
        // while proving nothing at all, and this file has already shipped one test that did exactly
        // that. Two enum options exist; fewer means the walk stopped finding them.
        assertTrue(enums.size() >= 2, "expected at least two enum options, found " + enums);
        for (Class<?> type : enums) {
            for (Object constant : type.getEnumConstants()) {
                // Reflectively, not through a Cloth interface. These enums stopped implementing
                // SelectionListEntry.Translatable because that interface is a GUI class, and having
                // it on a settings enum stopped the mod booting on any server without Cloth. The
                // method is still there and ClothScreen reads it the same way.
                String key = (String) constant.getClass().getMethod("getKey").invoke(constant);
                assertTrue(key.startsWith("text.autoconfig.pathweaver.option."),
                    "enum key format drifted: " + key);
                assertTrue(lang.has(key), "missing language entry for " + key);
                assertFalse(lang.get(key).getAsString().isBlank(), key);
            }
        }
    }

    @Test
    void enabledMasterIsFirstVisibleDefaultOnAndHasHonestDrainTooltip() throws Exception {
        List<String> configFields = Arrays.stream(PathWeaverConfig.class.getDeclaredFields())
            .filter(field -> !Modifier.isStatic(field.getModifiers()))
            .filter(field -> !field.isAnnotationPresent(ConfigEntry.Gui.Excluded.class))
            .map(Field::getName)
            .toList();
        assertEquals("enabled", configFields.getFirst());
        assertFalse(configFields.contains("asyncEnabled"));
        assertFalse(configFields.contains("syncFallbackOnly"));
        assertTrue(new PathWeaverConfig().enabled);

        JsonObject lang = JsonParser.parseString(Files.readString(
            RESOURCES.resolve(Path.of("assets", "pathweaver", "lang", "en_us.json"))))
            .getAsJsonObject();
        // The master switch must keep saying what OFF actually does -- it drains rather than
        // cancelling -- however the wording is later revised.
        String drain = lang.get("text.autoconfig.pathweaver.option.enabled.@Tooltip[1]").getAsString();
        assertTrue(drain.contains("already accepted finish"), drain);
    }

    /**
     * Every generated option must have a label and exactly the tooltip lines it declares.
     *
     * <p>Cloth renders a key it cannot resolve as the literal key, so a missing entry does not fail
     * anything — it puts {@code text.autoconfig.pathweaver.option.workerFailureLimit} on screen in
     * front of a user. This project has already shipped that once, when a tier's enum labels had no
     * language entries, and the settings screen showed bare constants for a whole release.
     *
     * <p>The two directions are separate defects and are asserted separately:
     * <ul>
     *   <li>a declared line with no entry renders as a raw key;
     *   <li>an entry past the declared count is text nobody will ever see — the author wrote a
     *       sentence, lowered {@code count}, and the sentence silently stopped rendering.
     * </ul>
     *
     * <p>The client game test draws this screen, but it captures one screenshot of the top of the
     * General category, and these fields are below the fold. Nothing else looks at them.
     */
    @Test
    void everyOptionHasALabelAndExactlyTheTooltipLinesItDeclares() throws Exception {
        JsonObject lang = JsonParser.parseString(Files.readString(
            RESOURCES.resolve(Path.of("assets", "pathweaver", "lang", "en_us.json"))))
            .getAsJsonObject();

        List<String> problems = new java.util.ArrayList<>();
        int checkedFields = 0;
        int checkedLines = 0;
        for (Field field : PathWeaverConfig.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) continue;
            if (field.isAnnotationPresent(ConfigEntry.Gui.Excluded.class)) continue;
            checkedFields++;

            String base = "text.autoconfig.pathweaver.option." + field.getName();
            if (!lang.has(base) || lang.get(base).getAsString().isBlank()) {
                problems.add("no label for " + field.getName() + " (key " + base + ")");
            }

            ConfigEntry.Gui.Tooltip tooltip = field.getAnnotation(ConfigEntry.Gui.Tooltip.class);
            int declared = tooltip == null ? 0 : tooltip.count();
            for (int i = 0; i < declared; i++) {
                String key = base + ".@Tooltip[" + i + "]";
                checkedLines++;
                if (!lang.has(key) || lang.get(key).getAsString().isBlank()) {
                    problems.add("declared tooltip line missing: " + key);
                }
            }
            // One past the end: an orphan line that no longer renders.
            String orphan = base + ".@Tooltip[" + declared + "]";
            if (lang.has(orphan)) {
                problems.add("tooltip line beyond the declared count of " + declared
                    + " never renders: " + orphan);
            }
        }

        assertTrue(checkedFields > 0, "no config fields were inspected, so this asserts nothing");
        assertTrue(checkedLines > 0, "no tooltip lines were inspected, so this asserts nothing");
        assertEquals(List.of(), problems,
            "the settings screen would show raw keys or hide written text");
    }

    /**
     * No language entry may name an option that no longer exists.
     *
     * <p>The reverse of the check above, and the one that catches a renamed field: the old key stays
     * behind, reads as covered, and the new field's own key is the one that is missing. Renaming
     * {@code asyncEnabled} to {@code enabled} is exactly this shape and has happened here before.
     */
    @Test
    void noLanguageEntryNamesAnOptionThatDoesNotExist() throws Exception {
        JsonObject lang = JsonParser.parseString(Files.readString(
            RESOURCES.resolve(Path.of("assets", "pathweaver", "lang", "en_us.json"))))
            .getAsJsonObject();

        java.util.Set<String> fields = new java.util.HashSet<>();
        for (Field field : PathWeaverConfig.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers())) fields.add(field.getName());
        }

        String prefix = "text.autoconfig.pathweaver.option.";
        List<String> orphans = new java.util.ArrayList<>();
        for (String key : lang.keySet()) {
            if (!key.startsWith(prefix)) continue;
            String rest = key.substring(prefix.length());
            int dot = rest.indexOf('.');
            String owner = dot < 0 ? rest : rest.substring(0, dot);
            // Enum-constant labels live under the option that declares them, e.g.
            // option.compatibilityTier.UNSAFE -- those resolve to the field name too.
            if (!fields.contains(owner)) orphans.add(key);
        }
        assertEquals(List.of(), orphans,
            "language entries for options that no longer exist -- a rename left the old key behind");
    }

    @Test
    void staticImplementationFieldsAreExcludedFromGeneratedGui() {
        List<String> exposedStatics = Arrays.stream(PathWeaverConfig.class.getDeclaredFields())
            .filter(field -> Modifier.isStatic(field.getModifiers()))
            .filter(field -> !field.isAnnotationPresent(ConfigEntry.Gui.Excluded.class))
            .map(Field::getName)
            .toList();

        assertEquals(List.of(), exposedStatics,
            "AutoConfig otherwise creates entries for constants/INSTANCE and Save crashes on final fields");
    }

    @Test
    void everyGeneratedCategoryIsTranslatedAndContainsAtLeastOneVisibleOption() throws Exception {
        JsonObject lang = JsonParser.parseString(Files.readString(
            RESOURCES.resolve(Path.of("assets", "pathweaver", "lang", "en_us.json"))))
            .getAsJsonObject();
        Map<String, Integer> visibleOptionsByCategory = new LinkedHashMap<>();
        java.util.Set<String> generatedCategories = new java.util.LinkedHashSet<>();

        for (Field field : PathWeaverConfig.class.getDeclaredFields()) {
            ConfigEntry.Category category = field.getAnnotation(ConfigEntry.Category.class);
            assertNotNull(category, field.getName()
                + " would make AutoConfig materialize its raw implicit default category before exclusion");
            assertNotEquals("default", category.value(), field.getName());
            generatedCategories.add(category.value());
            if (!field.isAnnotationPresent(ConfigEntry.Gui.Excluded.class)) {
                visibleOptionsByCategory.merge(category.value(), 1, Integer::sum);
            }
        }

        for (String category : generatedCategories) {
            String key = "text.autoconfig.pathweaver.category." + category;
            assertTrue(lang.has(key), key);
            assertFalse(lang.get(key).getAsString().isBlank(), key);
            assertTrue(visibleOptionsByCategory.getOrDefault(category, 0) > 0,
                category + " would render as an empty category");
        }
        assertEquals(java.util.Set.of("general", "performance", "repath"),
            generatedCategories);
    }

    @Test
    void everyOptionHasAPlainLanguageTooltipAndIntentionalCategory() throws Exception {
        JsonObject lang = JsonParser.parseString(Files.readString(
            RESOURCES.resolve(Path.of("assets", "pathweaver", "lang", "en_us.json"))))
            .getAsJsonObject();
        Map<String, String> expectedCategories = new LinkedHashMap<>();
        expectedCategories.put("enabled", "general");
        expectedCategories.put("allowModdedMobAsync", "general");
        expectedCategories.put("compatibilityTier", "general");
        expectedCategories.put("trustedMods", "general");
        expectedCategories.put("poolThreads", "performance");
        expectedCategories.put("maxInFlight", "performance");
        // Performance, not general: it decides WHICH searches the mod takes on, which is the
        // same kind of dial as worker capacity. It is the one option here with a behaviour
        // cost -- one tick before a brain mob sets off -- and that is stated in its tooltip
        // rather than by filing it beside the safety switches.
        expectedCategories.put("brainSinkAsync", "performance");
        // All three cache options together under performance, including the age limit, even though
        // "repath" also holds a maxResultAgeTicks. They are different limits on different things --
        // one bounds an in-flight result, the other a stored route -- and splitting the cache across
        // two screens to put its age limit next to a similarly named setting would invite exactly
        // the confusion between them that the tooltips have to work to prevent.
        expectedCategories.put("resultCacheMode", "performance");
        expectedCategories.put("resultCacheMaxAgeTicks", "performance");
        expectedCategories.put("resultCacheMaxEntries", "performance");
        // Performance, with the rest of the dials that trade behaviour for tick time, and NOT under
        // general beside the safety switches. These three are the only settings in the mod that
        // change what a mob does, and filing them next to the master switch would suggest they are
        // safety controls rather than a saving with a stated cost.
        expectedCategories.put("lodEnabled", "performance");
        expectedCategories.put("lodMinDistanceBlocks", "performance");
        expectedCategories.put("lodIntervalTicks", "performance");
        expectedCategories.put("repathToleranceBlocks", "repath");
        expectedCategories.put("stalenessMoveThreshold", "repath");
        expectedCategories.put("maxResultAgeTicks", "repath");
        // Safety settings, so they sit with the master switch and the compatibility risk dial rather
        // than under worker capacity: they change what the mod does about a failure, not how much
        // work it will take on.
        expectedCategories.put("workerFailureLimit", "general");
        expectedCategories.put("workerFailureWindowTicks", "general");

        for (Field field : PathWeaverConfig.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())
                    || field.isAnnotationPresent(ConfigEntry.Gui.Excluded.class)) continue;
            assertTrue(field.isAnnotationPresent(ConfigEntry.Gui.Tooltip.class), field.getName());
            ConfigEntry.Category category = field.getAnnotation(ConfigEntry.Category.class);
            assertNotNull(category, field.getName());
            assertEquals(expectedCategories.get(field.getName()), category.value(), field.getName());
            assertTrue(lang.has("text.autoconfig.pathweaver.option." + field.getName()), field.getName());
            // Tooltips are multi-line. A single long string is what Cloth renders as one unbroken
            // run of text, so the per-line limit is what keeps a setting explainable without
            // becoming unreadable in game; the count on the annotation must match the keys present.
            int lines = field.getAnnotation(ConfigEntry.Gui.Tooltip.class).count();
            assertTrue(lines >= 1, field.getName() + " must declare at least one tooltip line");
            String base = "text.autoconfig.pathweaver.option." + field.getName() + ".@Tooltip";
            assertFalse(lang.has(base),
                base + " must not exist alongside indexed lines; Cloth would ignore one of them");
            for (int i = 0; i < lines; i++) {
                String key = base + "[" + i + "]";
                assertTrue(lang.has(key), key);
                String line = lang.get(key).getAsString();
                assertFalse(line.isBlank(), key);
                assertTrue(line.length() <= 120, key + " is " + line.length() + " chars");
            }
            assertFalse(lang.has(base + "[" + lines + "]"),
                field.getName() + " has more tooltip lines than the annotation declares");
        }
        assertTrue(lang.has("text.autoconfig.pathweaver.category.general"));
        assertTrue(lang.has("text.autoconfig.pathweaver.category.performance"));
        assertTrue(lang.has("text.autoconfig.pathweaver.category.repath"));
    }

    /**
     * A setting that no longer exists is ignored on read and gone on the next write.
     *
     * <p>Was written against Cloth's ConfigHolder. The holder is gone, and the property is not: a
     * config file carried forward from an older version must not resurrect a field the mod stopped
     * honouring, and must not keep writing it back out either.
     */
    @Test
    void removedDistanceThrottleFieldIsIgnoredAndDroppedOnNextSave(@TempDir Path tempDir) throws Exception {
        Path configPath = tempDir.resolve("config").resolve("pathweaver.json");
        Files.createDirectories(configPath.getParent());
        Files.writeString(configPath, """
            {"asyncEnabled":false,"distanceThrottleEnabled":true}
            """);
        ConfigFile file = new ConfigFile(configPath);
        PathWeaverConfig loaded = file.deserialize();
        assertFalse(loaded.enabled, "known explicit-off value survives upgrade");
        file.serialize(loaded);
        JsonObject saved = JsonParser.parseString(Files.readString(configPath)).getAsJsonObject();
        assertFalse(saved.has("distanceThrottleEnabled"), "retired unknown field is dropped on save");
    }

    /**
     * Saving from the settings screen writes the file, publishes the runtime, and does NOT publish
     * the object the screen is still editing.
     *
     * <p>This is the aliasing hazard the 0.9.0 review found, and it survives the move off AutoConfig
     * unchanged in substance: whatever object the screen holds must not become the live settings, or
     * every keystroke is live before anyone presses save. The mechanism is different now, so the test
     * is rewritten rather than deleted; deleting it would have quietly retired the assertion that
     * caught the bug.
     */
    @Test
    void toggleSaveRoundTripsToDiskAndRuntime(@TempDir Path tempDir) throws Exception {
        Path configPath = tempDir.resolve("config").resolve("pathweaver.json");
        Files.createDirectories(configPath.getParent());
        PathWeaverConfig previousRuntime = PathWeaverConfig.get();
        try {
            PathWeaverConfig sentinel = new PathWeaverConfig();
            sentinel.enabled = false;
            PathWeaverConfig.set(sentinel);

            // What the screen edits: a detached copy, exactly as ClothScreen takes one.
            PathWeaverConfig editing = PathWeaverConfig.copyOf(PathWeaverConfig.get());
            assertNotSame(PathWeaverConfig.get(), editing,
                "the screen would be editing the live settings object");
            editing.enabled = true;
            editing.poolThreads = -3;
            editing.maxInFlight = 0;

            ConfigFile file = new ConfigFile(configPath);
            file.serialize(editing);
            PathWeaverConfig.set(editing);

            assertNotSame(editing, PathWeaverConfig.get(),
                "publication handed out the object the screen is still editing");
            assertTrue(PathWeaverConfig.get().enabled, "live runtime config");
            assertEquals(0, PathWeaverConfig.get().poolThreads, "normalized runtime poolThreads");
            assertEquals(1, PathWeaverConfig.get().maxInFlight, "normalized runtime maxInFlight");

            JsonObject disk = JsonParser.parseString(Files.readString(configPath)).getAsJsonObject();
            assertTrue(disk.get("enabled").getAsBoolean(), "config/pathweaver.json");
            assertEquals(0, disk.get("poolThreads").getAsInt(), "normalized disk poolThreads");
            assertEquals(1, disk.get("maxInFlight").getAsInt(), "normalized disk maxInFlight");
        } finally {
            PathWeaverConfig.set(previousRuntime);
        }
    }

    /**
     * The screen's own entry point takes a copy, rather than each caller remembering to.
     *
     * <p>{@code ClothScreen} is not constructed here because building it needs a running client.
     * What is checked is the contract it relies on, which is the part that can silently regress.
     */
    @Test
    void copyOfHandsBackADetachedObject() {
        PathWeaverConfig live = PathWeaverConfig.get();
        PathWeaverConfig copy = PathWeaverConfig.copyOf(live);
        assertNotSame(live, copy, "copyOf returned the live settings object");
        copy.enabled = !copy.enabled;
        assertEquals(live.enabled, PathWeaverConfig.get().enabled,
            "editing the copy changed the running settings");
    }
}
