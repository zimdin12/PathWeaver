package dev.pathweaver.config;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

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
    void asyncToggleIsFirstAndHasTheRequiredTooltip() throws Exception {
        List<String> configFields = Arrays.stream(PathWeaverConfig.class.getDeclaredFields())
            .filter(field -> !java.lang.reflect.Modifier.isStatic(field.getModifiers()))
            .map(java.lang.reflect.Field::getName)
            .toList();
        assertEquals("asyncEnabled", configFields.getFirst());
        assertTrue(configFields.indexOf("syncFallbackOnly") > configFields.indexOf("asyncEnabled"));

        JsonObject lang = JsonParser.parseString(Files.readString(
            RESOURCES.resolve(Path.of("assets", "pathweaver", "lang", "en_us.json"))))
            .getAsJsonObject();
        assertEquals("Experimental off-thread pathfinding; disable if you see issues",
            lang.get("text.autoconfig.pathweaver.option.asyncEnabled.@Tooltip").getAsString());
    }
}
