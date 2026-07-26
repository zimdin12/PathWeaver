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
        assertEquals("Stops new off-thread searches and repath reuse; accepted searches finish, then routing is vanilla-sync.",
            lang.get("text.autoconfig.pathweaver.option.enabled.@Tooltip").getAsString());
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
        expectedCategories.put("repathElisionEnabled", "general");
        expectedCategories.put("poolThreads", "performance");
        expectedCategories.put("maxInFlight", "performance");
        expectedCategories.put("repathToleranceBlocks", "repath");
        expectedCategories.put("stalenessMoveThreshold", "repath");
        expectedCategories.put("maxResultAgeTicks", "repath");

        for (Field field : PathWeaverConfig.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())
                    || field.isAnnotationPresent(ConfigEntry.Gui.Excluded.class)) continue;
            assertTrue(field.isAnnotationPresent(ConfigEntry.Gui.Tooltip.class), field.getName());
            ConfigEntry.Category category = field.getAnnotation(ConfigEntry.Category.class);
            assertNotNull(category, field.getName());
            assertEquals(expectedCategories.get(field.getName()), category.value(), field.getName());
            assertTrue(lang.has("text.autoconfig.pathweaver.option." + field.getName()), field.getName());
            String tooltipKey = "text.autoconfig.pathweaver.option." + field.getName() + ".@Tooltip";
            assertTrue(lang.has(tooltipKey), tooltipKey);
            String tooltip = lang.get(tooltipKey).getAsString();
            assertFalse(tooltip.isBlank(), tooltipKey);
            assertTrue(tooltip.length() <= 120, tooltipKey);
        }
        assertTrue(lang.has("text.autoconfig.pathweaver.category.general"));
        assertTrue(lang.has("text.autoconfig.pathweaver.category.performance"));
        assertTrue(lang.has("text.autoconfig.pathweaver.category.repath"));
    }

    @Test
    void removedDistanceThrottleFieldIsIgnoredAndDroppedOnNextSave(@TempDir Path tempDir) throws Exception {
        Path configPath = tempDir.resolve("config").resolve("pathweaver.json");
        Files.createDirectories(configPath.getParent());
        Files.writeString(configPath, """
            {"asyncEnabled":false,"distanceThrottleEnabled":true}
            """);
        ConfigHolder<PathWeaverConfig> holder = new TestConfigHolder(new PathWeaverConfigSerializer(configPath));
        assertTrue(holder.load());
        assertFalse(holder.getConfig().enabled, "known explicit-off value survives upgrade");
        holder.save();
        JsonObject saved = JsonParser.parseString(Files.readString(configPath)).getAsJsonObject();
        assertFalse(saved.has("distanceThrottleEnabled"), "retired unknown field is dropped on save");
    }

    @Test
    void toggleSaveRoundTripsToDiskAndRuntime(@TempDir Path tempDir) throws Exception {
        Path configPath = tempDir.resolve("config").resolve("pathweaver.json");
        PathWeaverConfig previousRuntime = PathWeaverConfig.get();
        ConfigHolder<PathWeaverConfig> holder = new TestConfigHolder(new PathWeaverConfigSerializer(configPath));
        PathWeaverConfig originalHolderConfig = holder.getConfig();
        boolean desired = !originalHolderConfig.enabled;
        PathWeaverConfig sentinel = new PathWeaverConfig();
        sentinel.enabled = !desired;
        try {
            holder.registerSaveListener(PathWeaverConfig::onSave);
            PathWeaverConfig.set(sentinel);
            holder.getConfig().enabled = desired;
            holder.getConfig().poolThreads = -3;
            holder.getConfig().maxInFlight = 0;
            holder.save();

            assertSame(holder.getConfig(), PathWeaverConfig.get(), "save listener publishes holder object");
            assertEquals(desired, holder.getConfig().enabled, "AutoConfig holder");
            assertEquals(desired, PathWeaverConfig.get().enabled, "live runtime config");
            assertEquals(0, holder.getConfig().poolThreads, "normalized holder poolThreads");
            assertEquals(1, holder.getConfig().maxInFlight, "normalized holder maxInFlight");
            JsonObject disk = JsonParser.parseString(Files.readString(configPath)).getAsJsonObject();
            assertEquals(desired, disk.get("enabled").getAsBoolean(), "config/pathweaver.json");
            assertEquals(0, disk.get("poolThreads").getAsInt(), "normalized disk poolThreads");
            assertEquals(1, disk.get("maxInFlight").getAsInt(), "normalized disk maxInFlight");
        } finally {
            holder.setConfig(originalHolderConfig);
            PathWeaverConfig.set(previousRuntime);
        }
    }

    private static final class TestConfigHolder implements ConfigHolder<PathWeaverConfig> {
        private final ConfigSerializer<PathWeaverConfig> serializer;
        private PathWeaverConfig config = new PathWeaverConfig();
        private ConfigSerializeEvent.Save<PathWeaverConfig> saveListener;

        private TestConfigHolder(ConfigSerializer<PathWeaverConfig> serializer) {
            this.serializer = serializer;
        }

        @Override public Class<PathWeaverConfig> getConfigClass() { return PathWeaverConfig.class; }
        @Override public PathWeaverConfig getConfig() { return config; }
        @Override public void setConfig(PathWeaverConfig config) { this.config = config; }
        @Override public void registerSaveListener(ConfigSerializeEvent.Save<PathWeaverConfig> listener) {
            this.saveListener = listener;
        }
        @Override public void registerLoadListener(ConfigSerializeEvent.Load<PathWeaverConfig> listener) { }
        @Override public void resetToDefault() { config = serializer.createDefault(); }
        @Override public boolean load() {
            try {
                config = serializer.deserialize();
                return true;
            } catch (ConfigSerializer.SerializationException e) {
                return false;
            }
        }
        @Override public void save() {
            try {
                InteractionResult result = saveListener == null
                    ? InteractionResult.PASS
                    : saveListener.onSave(this, config);
                if (result != InteractionResult.FAIL) serializer.serialize(config);
            } catch (ConfigSerializer.SerializationException e) {
                throw new RuntimeException(e);
            }
        }
    }

}
