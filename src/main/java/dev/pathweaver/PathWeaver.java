package dev.pathweaver;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PathWeaver implements ModInitializer {
    public static final String MOD_ID = "pathweaver";
    public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOG.info("PathWeaver initializing");

        // In-game diagnostics. Until this existed, the only way to answer "does this cover my mobs,
        // and if not why" was to write a probe against the mod's internals.
        net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback.EVENT.register(
            (dispatcher, registry, environment) ->
                dev.pathweaver.command.PathWeaverCommand.register(dispatcher));

        // Load config/pathweaver.json ourselves. No GUI library involved: a dedicated server has
        // no settings screen to render and should not have to install one to read a JSON file.
        // Guarded so that any failure forces synchronous fail-closed defaults rather than silently
        // enabling async pathfinding on settings nobody verified.
        try {
            dev.pathweaver.config.ConfigFile file = new dev.pathweaver.config.ConfigFile();
            boolean firstRun = !file.exists();
            dev.pathweaver.config.ConfigLoad.Result loaded =
                dev.pathweaver.config.ConfigLoad.from(file);
            dev.pathweaver.config.PathWeaverConfig.publishLoaded(loaded.config(), loaded.failed());
            // Write the defaults out on a first run. AutoConfig used to do this and its absence was
            // invisible until a real server booted without it: the mod worked, and the file the page
            // tells a server owner to edit was never created, so there was nothing to edit and no
            // sign anything was wrong. Only on a first run, so a failed load never overwrites a file
            // somebody is in the middle of fixing.
            if (firstRun && !loaded.failed()) {
                try {
                    file.serialize(loaded.config());
                } catch (dev.pathweaver.config.ConfigFile.ConfigIoException writeFailed) {
                    LOG.warn("PathWeaver could not write a default config/pathweaver.json.",
                        writeFailed.getCause());
                }
            }
            if (loaded.failed()) {
                LOG.warn("PathWeaver config load failed; forcing synchronous pathfinding until a "
                    + "valid config is saved.", loaded.cause());
            }
        } catch (Throwable t) {
            dev.pathweaver.config.PathWeaverConfig.installFailClosedDefaults();
            LOG.warn("PathWeaver config load failed; forcing synchronous pathfinding.", t);
        }

        dev.pathweaver.gate.ForeignMixinScanner.scanAndPopulate();

        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTING
            .register(s -> PathWeaverRuntime.get().onServerStarting(s));
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPING
            .register(s -> PathWeaverRuntime.get().onServerStopping(s));
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK
            .register(s -> PathWeaverRuntime.get().onEndTick(s));
    }
}
