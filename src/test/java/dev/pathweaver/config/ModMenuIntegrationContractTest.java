package dev.pathweaver.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import me.shedaniel.autoconfig.ConfigHolder;
import me.shedaniel.autoconfig.annotation.ConfigEntry;
import me.shedaniel.autoconfig.event.ConfigSerializeEvent;
import me.shedaniel.autoconfig.serializer.ConfigSerializer;
import net.minecraft.world.InteractionResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
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
    void asyncToggleIsFirstAndHasTheRequiredTooltip() throws Exception {
        List<String> configFields = Arrays.stream(PathWeaverConfig.class.getDeclaredFields())
            .filter(field -> !Modifier.isStatic(field.getModifiers()))
            .map(Field::getName)
            .toList();
        assertEquals("asyncEnabled", configFields.getFirst());
        assertTrue(configFields.indexOf("syncFallbackOnly") > configFields.indexOf("asyncEnabled"));

        JsonObject lang = JsonParser.parseString(Files.readString(
            RESOURCES.resolve(Path.of("assets", "pathweaver", "lang", "en_us.json"))))
            .getAsJsonObject();
        assertEquals("Experimental off-thread pathfinding; disable if you see issues",
            lang.get("text.autoconfig.pathweaver.option.asyncEnabled.@Tooltip").getAsString());
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
    void everyOptionHasAPlainLanguageTooltipAndIntentionalCategory() throws Exception {
        JsonObject lang = JsonParser.parseString(Files.readString(
            RESOURCES.resolve(Path.of("assets", "pathweaver", "lang", "en_us.json"))))
            .getAsJsonObject();
        Map<String, String> expectedCategories = new LinkedHashMap<>();
        expectedCategories.put("asyncEnabled", "general");
        expectedCategories.put("allowModdedMobAsync", "general");
        expectedCategories.put("repathElisionEnabled", "general");
        expectedCategories.put("poolThreads", "performance");
        expectedCategories.put("maxInFlight", "performance");
        expectedCategories.put("syncFallbackOnly", "general");
        expectedCategories.put("repathToleranceBlocks", "repath");
        expectedCategories.put("stalenessMoveThreshold", "repath");
        expectedCategories.put("maxResultAgeTicks", "repath");

        for (Field field : PathWeaverConfig.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) continue;
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
        ConfigHolder<PathWeaverConfig> holder = new TestConfigHolder(new TestDiskSerializer(configPath));
        assertTrue(holder.load());
        assertFalse(holder.getConfig().asyncEnabled, "known explicit-off value survives upgrade");
        holder.save();
        JsonObject saved = JsonParser.parseString(Files.readString(configPath)).getAsJsonObject();
        assertFalse(saved.has("distanceThrottleEnabled"), "retired unknown field is dropped on save");
    }

    @Test
    void toggleSaveRoundTripsToDiskAndRuntime(@TempDir Path tempDir) throws Exception {
        Path configPath = tempDir.resolve("config").resolve("pathweaver.json");
        PathWeaverConfig previousRuntime = PathWeaverConfig.get();
        ConfigHolder<PathWeaverConfig> holder = new TestConfigHolder(new TestDiskSerializer(configPath));
        PathWeaverConfig originalHolderConfig = holder.getConfig();
        boolean desired = !originalHolderConfig.asyncEnabled;
        PathWeaverConfig sentinel = new PathWeaverConfig();
        sentinel.asyncEnabled = !desired;
        try {
            holder.registerSaveListener(PathWeaverConfig::onSave);
            PathWeaverConfig.set(sentinel);
            holder.getConfig().asyncEnabled = desired;
            holder.getConfig().poolThreads = -3;
            holder.getConfig().maxInFlight = 0;
            holder.save();

            assertSame(holder.getConfig(), PathWeaverConfig.get(), "save listener publishes holder object");
            assertEquals(desired, holder.getConfig().asyncEnabled, "AutoConfig holder");
            assertEquals(desired, PathWeaverConfig.get().asyncEnabled, "live runtime config");
            assertEquals(0, holder.getConfig().poolThreads, "normalized holder poolThreads");
            assertEquals(1, holder.getConfig().maxInFlight, "normalized holder maxInFlight");
            JsonObject disk = JsonParser.parseString(Files.readString(configPath)).getAsJsonObject();
            assertEquals(desired, disk.get("asyncEnabled").getAsBoolean(), "config/pathweaver.json");
            assertEquals(0, disk.get("poolThreads").getAsInt(), "normalized disk poolThreads");
            assertEquals(1, disk.get("maxInFlight").getAsInt(), "normalized disk maxInFlight");
        } finally {
            holder.setConfig(originalHolderConfig);
            PathWeaverConfig.set(previousRuntime);
        }
    }

    private static final class TestConfigHolder implements ConfigHolder<PathWeaverConfig> {
        private final TestDiskSerializer serializer;
        private PathWeaverConfig config = new PathWeaverConfig();
        private ConfigSerializeEvent.Save<PathWeaverConfig> saveListener;

        private TestConfigHolder(TestDiskSerializer serializer) {
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

    private static final class TestDiskSerializer implements ConfigSerializer<PathWeaverConfig> {
        private final Path path;
        private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

        private TestDiskSerializer(Path path) {
            this.path = path;
        }

        @Override
        public void serialize(PathWeaverConfig config) throws SerializationException {
            try {
                Files.createDirectories(path.getParent());
                Files.writeString(path, gson.toJson(config));
            } catch (IOException e) {
                throw new SerializationException(e);
            }
        }

        @Override
        public PathWeaverConfig deserialize() throws SerializationException {
            if (!Files.exists(path)) return createDefault();
            try {
                return gson.fromJson(Files.readString(path), PathWeaverConfig.class);
            } catch (IOException e) {
                throw new SerializationException(e);
            }
        }

        @Override
        public PathWeaverConfig createDefault() {
            return new PathWeaverConfig();
        }
    }
}
