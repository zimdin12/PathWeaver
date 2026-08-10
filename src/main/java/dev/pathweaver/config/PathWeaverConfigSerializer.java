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
            // Write to a sibling temp file and move it into place, so a crash or a full disk
            // part-way through cannot leave truncated JSON where the config should be. Writing
            // directly over the live file left a window in which the next launch would read a
            // half-written file, fail closed, and silently lose the user's settings.
            java.nio.file.Path temp = path.resolveSibling(path.getFileName() + ".tmp");
            Files.writeString(temp, gson.toJson(current));
            try {
                Files.move(temp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException atomicUnsupported) {
                Files.move(temp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
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

            migrateRenamedTier(current);
            validateCurrentFieldTypes(current);
            migrateCompatibilityTier(current);
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
        strictOptionalInteger(raw, "poolThreads");
        strictOptionalInteger(raw, "maxInFlight");
        strictOptionalInteger(raw, "repathToleranceBlocks");
        strictOptionalNumber(raw, "stalenessMoveThreshold");
        strictOptionalInteger(raw, "maxResultAgeTicks");
        // Added with the fields themselves and not afterwards, because this is the file people
        // hand-edit: without these two, {"workerFailureLimit": "7"} was quietly coerced by GSON while
        // the identical mistake in maxInFlight was rejected.
        strictOptionalInteger(raw, "workerFailureLimit");
        strictOptionalInteger(raw, "workerFailureWindowTicks");
        strictOptionalEnum(raw, "compatibilityTier");
    }

    /**
     * Carry the retired {@code overrideCompatibilityScan} boolean onto the tier it became.
     *
     * <p>{@code true} was an explicit all-or-nothing bypass and maps to {@link CompatibilityTier#UNSAFE},
     * because mapping a deliberate loosening onto a stricter tier would silently override a choice
     * the operator made.
     *
     * <p>{@code false} maps to {@link CompatibilityTier#AUDITED}, pinned as a literal and
     * deliberately NOT to the shipped default.
     *
     * <p>This used to resolve through {@code new PathWeaverConfig().compatibilityTier} on the
     * argument that the default was the conservative tier, so deferring to it was the faithful
     * reading. That argument died when 0.5.0 made {@code UNSAFE} the default, and deferring became
     * an inversion: the key is literally named "override the compatibility scan", the operator
     * stored {@code false}, and the migration would have answered by turning the scan off entirely.
     * It is the one setting whose stored value is an explicit refusal of exactly what the new
     * default does, so it is the one setting that must not follow the default anywhere.
     *
     * <p>Pinning it to {@code STRICT} would read as more faithful still and would be worse: that
     * tier now denies any pack containing Fabric API, so a working install would go inert on upgrade
     * without the operator touching anything. {@code AUDITED} is the armed-scan tier that
     * {@code false} actually described.
     *
     * <p>An explicit {@code compatibilityTier} always wins, so a config carrying both is not
     * re-migrated.
     */
    private static void migrateCompatibilityTier(JsonObject raw) {
        if (!raw.has("overrideCompatibilityScan")) return;
        boolean override = strictBoolean(raw, "overrideCompatibilityScan", null);
        raw.remove("overrideCompatibilityScan");
        if (raw.has("compatibilityTier")) return;
        CompatibilityTier migrated = override ? CompatibilityTier.UNSAFE : CompatibilityTier.AUDITED;
        raw.addProperty("compatibilityTier", migrated.name());
        if (migrated == CompatibilityTier.AUDITED) {
            try {
                dev.pathweaver.PathWeaver.LOG.info("Your config still carried the retired "
                    + "overrideCompatibilityScan=false, which meant \"do not bypass the compatibility "
                    + "scan\". It has been migrated to compatibilityTier=AUDITED, which is what that "
                    + "asked for. Note this is STRICTER than a fresh 0.5.0 install, which ships "
                    + "UNSAFE — so on a heavily-modded pack expect PathWeaver to refuse, and see the "
                    + "world-start report for which mods are responsible.");
            } catch (Throwable ignored) {
                // Migrating must not depend on a logging backend being healthy.
            }
        }
    }

    /**
     * Rewrite tier names that no longer exist, so an upgrade does not fail closed on its own config.
     *
     * <p>{@code ALL} was renamed to {@code UNSAFE} because it never meant everything.
     *
     * <p>{@code STRICT} was removed outright. It honoured only structural proofs, and the exemption
     * covering Fabric API's own interaction module is a bounded call sample rather than a proof, so
     * it denied every install containing Fabric API — which this mod requires. It could not do
     * anything on any pack that has ever existed. Anyone holding it was running a mod that was
     * switched off, so mapping them to {@code AUDITED} is the smallest honest change: it is the
     * most conservative tier that still exists. Note this is NOT the shipped default and NOT what
     * a fresh install gives -- 0.5.0 ships {@code UNSAFE} -- and the literal is deliberate for the
     * same reason it is deliberate in {@code migrateCompatibilityTier}. It is logged rather than
     * done quietly, because it is still a loosening.
     */
    private static void migrateRenamedTier(JsonObject raw) {
        if (!raw.has("compatibilityTier")) return;
        JsonElement element = raw.get("compatibilityTier");
        if (!(element instanceof JsonPrimitive primitive) || !primitive.isString()) return;
        String stored = primitive.getAsString();
        if ("ALL".equals(stored)) {
            raw.addProperty("compatibilityTier", CompatibilityTier.UNSAFE.name());
        } else if ("STRICT".equals(stored)) {
            raw.addProperty("compatibilityTier", CompatibilityTier.AUDITED.name());
            try {
                dev.pathweaver.PathWeaver.LOG.warn("compatibilityTier=STRICT no longer exists and "
                    + "your config has been moved to AUDITED. STRICT denied every install that "
                    + "contained Fabric API, so it could never do anything; AUDITED is now the most "
                    + "conservative tier. This is a small loosening, so it is being said out loud.");
            } catch (Throwable ignored) {
                // Migrating must not depend on a logging backend being healthy.
            }
        }
    }

    private static void strictOptionalEnum(JsonObject raw, String key) {
        if (!raw.has(key)) return;
        JsonElement element = raw.get(key);
        if (!(element instanceof JsonPrimitive primitive) || !primitive.isString()) {
            throw new IllegalArgumentException(key + " must be a string");
        }
        String value = primitive.getAsString();
        for (CompatibilityTier tier : CompatibilityTier.values()) {
            if (tier.name().equals(value)) return;
        }
        throw new IllegalArgumentException(key + " is not a known tier: " + value);
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
