package dev.pathweaver.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import me.shedaniel.autoconfig.annotation.Config;
import me.shedaniel.autoconfig.serializer.ConfigSerializer;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Raw-JSON schema migration followed by strict current-model binding. */
public final class PathWeaverConfigSerializer implements ConfigSerializer<PathWeaverConfig> {
    private final Path path;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    public PathWeaverConfigSerializer(Config definition, Class<PathWeaverConfig> configClass) {
        this(FabricLoader.getInstance().getConfigDir().resolve(definition.name() + ".json"));
        if (configClass != PathWeaverConfig.class) {
            throw new IllegalArgumentException("PathWeaver serializer received " + configClass);
        }
    }

    PathWeaverConfigSerializer(Path path) {
        this.path = path;
    }

    @Override
    public void serialize(PathWeaverConfig config) throws SerializationException {
        try {
            PathWeaverConfig current = config == null ? createDefault() : config;
            current.validatePostLoad();
            Files.createDirectories(path.getParent());
            Files.writeString(path, gson.toJson(current));
        } catch (IOException | RuntimeException failure) {
            throw new SerializationException(failure);
        }
    }

    @Override
    public PathWeaverConfig deserialize() throws SerializationException {
        if (!Files.exists(path)) return createDefault();
        try {
            JsonElement parsed = JsonParser.parseString(Files.readString(path));
            if (!parsed.isJsonObject()) throw new IllegalArgumentException("config root must be an object");
            JsonObject raw = parsed.getAsJsonObject();
            int version = readVersion(raw);
            JsonObject current = raw.deepCopy();
            boolean enabled;
            if (version == PathWeaverConfig.CURRENT_CONFIG_VERSION) {
                rejectLegacyKeys(raw);
                enabled = strictBoolean(raw, "enabled", null);
            } else if (version == 0 || version == 1) {
                if (raw.has("enabled")) {
                    throw new IllegalArgumentException(
                        "legacy schema cannot also contain the v2 enabled key");
                }
                boolean legacyAsync = strictBoolean(raw, "asyncEnabled", true);
                boolean legacyPanic = strictBoolean(raw, "syncFallbackOnly", false);
                enabled = legacyAsync && !legacyPanic;
            } else {
                throw new IllegalArgumentException("unsupported configVersion " + version);
            }

            validateCurrentFieldTypes(current);
            current.remove("asyncEnabled");
            current.remove("syncFallbackOnly");
            current.addProperty("configVersion", PathWeaverConfig.CURRENT_CONFIG_VERSION);
            current.addProperty("enabled", enabled);
            PathWeaverConfig config = gson.fromJson(current, PathWeaverConfig.class);
            if (config == null) throw new IllegalArgumentException("config deserialized to null");
            config.validatePostLoad();
            return config;
        } catch (IOException | RuntimeException failure) {
            throw new SerializationException(failure);
        }
    }

    @Override
    public PathWeaverConfig createDefault() {
        return new PathWeaverConfig();
    }

    private static int readVersion(JsonObject raw) {
        if (!raw.has("configVersion")) return 0;
        JsonElement element = raw.get("configVersion");
        if (!(element instanceof JsonPrimitive primitive) || !primitive.isNumber()) {
            throw new IllegalArgumentException("configVersion must be an integer");
        }
        try {
            return primitive.getAsBigDecimal().intValueExact();
        } catch (ArithmeticException failure) {
            throw new IllegalArgumentException("configVersion must be an integer", failure);
        }
    }

    private static boolean strictBoolean(JsonObject raw, String key, Boolean defaultValue) {
        if (!raw.has(key)) {
            if (defaultValue != null) return defaultValue;
            throw new IllegalArgumentException("missing required boolean " + key);
        }
        JsonElement element = raw.get(key);
        if (!(element instanceof JsonPrimitive primitive) || !primitive.isBoolean()) {
            throw new IllegalArgumentException(key + " must be a boolean");
        }
        return primitive.getAsBoolean();
    }

    private static void rejectLegacyKeys(JsonObject raw) {
        if (raw.has("asyncEnabled") || raw.has("syncFallbackOnly")) {
            throw new IllegalArgumentException("v2 schema cannot contain legacy toggle keys");
        }
    }

    private static void validateCurrentFieldTypes(JsonObject raw) {
        strictOptionalBoolean(raw, "allowModdedMobAsync");
        strictOptionalBoolean(raw, "repathElisionEnabled");
        strictOptionalInteger(raw, "poolThreads");
        strictOptionalInteger(raw, "maxInFlight");
        strictOptionalInteger(raw, "repathToleranceBlocks");
        strictOptionalNumber(raw, "stalenessMoveThreshold");
        strictOptionalInteger(raw, "maxResultAgeTicks");
    }

    private static void strictOptionalBoolean(JsonObject raw, String key) {
        if (raw.has(key)) strictBoolean(raw, key, null);
    }

    private static void strictOptionalInteger(JsonObject raw, String key) {
        if (!raw.has(key)) return;
        JsonElement element = raw.get(key);
        if (!(element instanceof JsonPrimitive primitive) || !primitive.isNumber()) {
            throw new IllegalArgumentException(key + " must be an integer");
        }
        try {
            primitive.getAsBigDecimal().intValueExact();
        } catch (ArithmeticException failure) {
            throw new IllegalArgumentException(key + " must be an integer", failure);
        }
    }

    private static void strictOptionalNumber(JsonObject raw, String key) {
        if (!raw.has(key)) return;
        JsonElement element = raw.get(key);
        if (!(element instanceof JsonPrimitive primitive) || !primitive.isNumber()) {
            throw new IllegalArgumentException(key + " must be a number");
        }
    }
}
