package dev.pathweaver.config;

import me.shedaniel.autoconfig.annotation.Config;
import me.shedaniel.autoconfig.serializer.ConfigSerializer;

import java.nio.file.Path;

/**
 * Cloth's view of {@link ConfigFile}, and nothing else.
 *
 * <p>All of the reading, migration and strict validation lives in {@code ConfigFile}, which depends
 * on nothing but Gson and the JDK. This class exists so that Cloth, when it is installed, can be
 * handed something shaped the way it expects. It is the only file in the config package that knows
 * Cloth exists, so removing Cloth removes this file and nothing else.
 *
 * <p>It holds no logic on purpose. A serializer that adapted and also decided would be a second place
 * for the settings rules to live, and the settings rules are the part that must not fork.
 */
public final class PathWeaverConfigSerializer implements ConfigSerializer<PathWeaverConfig> {

    private final ConfigFile file;

    public PathWeaverConfigSerializer(Config definition, Class<PathWeaverConfig> configClass) {
        if (configClass != PathWeaverConfig.class) {
            throw new IllegalArgumentException("PathWeaver serializer received " + configClass);
        }
        this.file = new ConfigFile(
            net.fabricmc.loader.api.FabricLoader.getInstance()
                .getConfigDir().resolve(definition.name() + ".json"));
    }

    PathWeaverConfigSerializer(Path path) {
        this.file = new ConfigFile(path);
    }

    @Override
    public void serialize(PathWeaverConfig config) throws SerializationException {
        try {
            file.serialize(config);
        } catch (ConfigFile.ConfigIoException failure) {
            throw new SerializationException(failure.getCause());
        }
    }

    @Override
    public PathWeaverConfig deserialize() throws SerializationException {
        try {
            return file.deserialize();
        } catch (ConfigFile.ConfigIoException failure) {
            throw new SerializationException(failure.getCause());
        }
    }

    @Override
    public PathWeaverConfig createDefault() {
        return file.createDefault();
    }
}
