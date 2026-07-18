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

        // Register Cloth AutoConfig (persists config/pathweaver.json; GUI via ModMenu when present).
        // Guarded so a config-API mismatch forces synchronous fail-closed defaults rather than
        // silently enabling async or breaking dedicated-server startup.
        try {
            java.util.concurrent.atomic.AtomicBoolean loadFailed =
                new java.util.concurrent.atomic.AtomicBoolean();
            me.shedaniel.autoconfig.ConfigHolder<dev.pathweaver.config.PathWeaverConfig> holder =
                me.shedaniel.autoconfig.AutoConfig.register(
                dev.pathweaver.config.PathWeaverConfig.class,
                (definition, configClass) -> new dev.pathweaver.config.LoadFailureTrackingSerializer<>(
                    new me.shedaniel.autoconfig.serializer.GsonConfigSerializer<>(
                        definition, configClass), loadFailed));
            holder.registerSaveListener(dev.pathweaver.config.PathWeaverConfig::onSave);
            dev.pathweaver.config.PathWeaverConfig.publishLoaded(
                holder.getConfig(), loadFailed.get());
            if (loadFailed.get()) {
                LOG.warn("PathWeaver config load failed; forcing synchronous pathfinding until a valid config is saved.");
            }
        } catch (Throwable t) {
            dev.pathweaver.config.PathWeaverConfig.installFailClosedDefaults();
            LOG.warn("PathWeaver config registration failed; forcing synchronous pathfinding.", t);
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
