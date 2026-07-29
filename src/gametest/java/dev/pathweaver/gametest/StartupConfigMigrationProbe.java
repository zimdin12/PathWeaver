package dev.pathweaver.gametest;

import dev.pathweaver.config.PathWeaverConfig;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;

/** Process-start probe used only by the GameTest source set's real-file migration matrix. */
public final class StartupConfigMigrationProbe implements ModInitializer {
    @Override
    public void onInitialize() {
        String expected = System.getProperty("pathweaver.test.expectedStartupEnabled");
        if (expected == null) return;
        ServerLifecycleEvents.SERVER_STARTING.register(server -> validate(expected));
    }

    private static void validate(String expected) {
        Path configDir = FabricLoader.getInstance().getConfigDir();
        Path configFile = configDir.resolve("pathweaver.json");
        boolean expectedEnabled = Boolean.parseBoolean(expected);
        boolean actualEnabled = PathWeaverConfig.get().enabled;
        System.out.println("PATHWEAVER_STARTUP_CONFIG_DIR=" + configDir.toAbsolutePath().normalize());
        System.out.println("PATHWEAVER_STARTUP_CONFIG_EXISTS=" + Files.exists(configFile));
        System.out.println("PATHWEAVER_STARTUP_ENABLED=" + actualEnabled);
        if (!Files.exists(configFile)) {
            throw new IllegalStateException("real startup fixture was not read: " + configFile);
        }
        if (actualEnabled != expectedEnabled) {
            throw new IllegalStateException("real startup config migration produced Enabled="
                + actualEnabled + ", expected=" + expectedEnabled);
        }
    }
}
