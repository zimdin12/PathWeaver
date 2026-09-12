package dev.pathweaver.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reads and writes {@code pathweaver.json}: raw-JSON schema migration, then strict binding to the
 * current model.
 *
 * <p>This is the whole of the persistence logic and it depends on nothing but Gson and the JDK. It
 * used to be spelled as an implementation of Cloth's {@code ConfigSerializer}, which meant the mod
 * could not read its own settings file without a GUI library installed. On a dedicated server that
 * library renders nothing and exists only to be a dependency. The logic never needed it; only the
 * interface did.
 *
 * <p>{@link PathWeaverConfigSerializer} is now a thin adapter that hands Cloth this class when Cloth
 * is present. Nothing here knows that exists.
 */
public final class ConfigFile {
    private final Path path;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    /** The shipped location: {@code config/pathweaver.json}. */
    public ConfigFile() {
        this(FabricLoader.getInstance().getConfigDir().resolve("pathweaver.json"));
    }

    public ConfigFile(Path path) {
        this.path = path;
    }

    /** Whether a settings file is already on disk, as opposed to this being a first run. */
    public boolean exists() {
        return Files.exists(path);
    }

    public void serialize(PathWeaverConfig config) throws ConfigIoException {
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
            throw new ConfigIoException(failure);
        }
    }

    public PathWeaverConfig deserialize() throws ConfigIoException {
        if (!Files.exists(path)) return createDefault();
        try {
            JsonElement parsed = JsonParser.parseString(Files.readString(path));
            if (!parsed.isJsonObject()) throw new IllegalArgumentException("config root must be an object");
            JsonObject raw = parsed.getAsJsonObject();
            int version = readVersion(raw);
            JsonObject current = raw.deepCopy();
            boolean enabled;
            // 2 and 3 share this branch because the schema did not change between them: v3 exists
            // to migrate a stored VALUE, not a shape. Both always write "enabled", so its absence is
            // unambiguous damage in either.
            if (version == PathWeaverConfig.CURRENT_CONFIG_VERSION || version == 2) {
                rejectLegacyKeys(raw);
                enabled = strictBoolean(raw, "enabled", null);
            } else if (version == 0 || version == 1) {
                if (raw.has("enabled")) {
                    throw new IllegalArgumentException(
                        "legacy schema cannot also contain the v2 enabled key");
                }
                // Deliberately permissive, and NOT an oversight to be tidied into line with the
                // v2 branch above. These two keys are being interpreted for FIDELITY: the migration
                // reproduces what the operator's own installation actually did, and under 0.1.x-0.4.x
                // a config lacking asyncEnabled ran with async ON. Cloth writes every field, so a
                // legacy file missing it was hand-written -- by someone who was running with it on.
                // Failing closed here would silently switch the mod off on upgrade for exactly those
                // people. v2's "enabled" is different: the current version always writes it, so its
                // absence is unambiguous damage.
                boolean legacyAsync = strictBoolean(raw, "asyncEnabled", true);
                boolean legacyPanic = strictBoolean(raw, "syncFallbackOnly", false);
                enabled = legacyAsync && !legacyPanic;
            } else {
                throw new IllegalArgumentException("unsupported configVersion " + version);
            }

            migrateRenamedTier(current);
            validateCurrentFieldTypes(current);
            migrateCompatibilityTier(current, version);
            // After the type check, so a hand-edited non-integer is still rejected rather than
            // migrated.
            migrateRepathTolerance(current, version);
            current.remove("asyncEnabled");
            current.remove("syncFallbackOnly");
            current.addProperty("configVersion", PathWeaverConfig.CURRENT_CONFIG_VERSION);
            current.addProperty("enabled", enabled);
            PathWeaverConfig config = gson.fromJson(current, PathWeaverConfig.class);
            if (config == null) throw new IllegalArgumentException("config deserialized to null");
            config.validatePostLoad();
            return config;
        } catch (IOException | RuntimeException failure) {
            throw new ConfigIoException(failure);
        }
    }

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

    /** A JSON array of strings, or absent. Explicit null is a type error, as it is everywhere else. */
    private static void strictOptionalStringList(JsonObject raw, String key) {
        if (!raw.has(key)) return;
        com.google.gson.JsonElement value = raw.get(key);
        if (!value.isJsonArray()) {
            throw new IllegalArgumentException(key + " must be an array of strings");
        }
        for (com.google.gson.JsonElement element : value.getAsJsonArray()) {
            if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
                throw new IllegalArgumentException(key + " must contain only strings");
            }
        }
    }

    /**
     * Keys read before this runs, each by a check of its own that this must not duplicate.
     *
     * <p>{@code configVersion} chooses the migration branch, so it is read first. {@code enabled} is
     * required at v2 and v3 and deliberately optional in the legacy branch, which is a rule about
     * fidelity to old installations that no type check can express.
     */
    private static final java.util.Set<String> READ_ELSEWHERE =
        java.util.Set.of("configVersion", "enabled");

    /**
     * Every persisted field must survive a hand edit, or be rejected loudly.
     *
     * <p>This is the file people open in a text editor, so a wrong type is a normal event rather than
     * a corrupt one, and Gson coerces several of them without complaint: {@code "7"} became 7 and
     * {@code null} overwrote an initialised list.
     *
     * <p>The fields are DISCOVERED from the config class, not listed. The listed version was wrong
     * three separate times, each time in the same way: a field was added, the list was not, and the
     * gap was found later by someone reading the file rather than by anything failing. Two of those
     * shipped. Deriving the checks from the declared type means a new field is covered by existing
     * it, and a field whose type has no check refuses to load rather than passing unvalidated.
     */
    private static void validateCurrentFieldTypes(JsonObject raw) {
        for (java.lang.reflect.Field field : PathWeaverConfig.class.getDeclaredFields()) {
            int modifiers = field.getModifiers();
            // transient is the language's own word for "not persisted", and Gson honours it, so a
            // transient field never appears in the file and has nothing to type-check. Deriving the
            // exclusion from the modifier rather than from a name keeps this a walk rather than a
            // list with one exception in it.
            if (java.lang.reflect.Modifier.isStatic(modifiers)
                || java.lang.reflect.Modifier.isTransient(modifiers)) {
                continue;
            }
            String key = field.getName();
            if (READ_ELSEWHERE.contains(key)) continue;
            Class<?> type = field.getType();
            if (type == boolean.class) {
                strictOptionalBoolean(raw, key);
            } else if (type == int.class || type == long.class) {
                strictOptionalInteger(raw, key);
            } else if (type == double.class || type == float.class) {
                strictOptionalNumber(raw, key);
            } else if (type.isEnum()) {
                strictOptionalEnum(raw, key, type);
            } else if (java.util.List.class.isAssignableFrom(type)) {
                strictOptionalStringList(raw, key);
            } else {
                // Fail closed. An unchecked field is how the last three got through, and a settings
                // file that refuses to load is louder than one that loads something wrong.
                throw new IllegalStateException(
                    "no strict check for " + key + " of type " + type.getName());
            }
        }
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
    private static void migrateCompatibilityTier(JsonObject raw, int version) {
        if (!raw.has("overrideCompatibilityScan")) {
            // A v0/v1 config carrying NEITHER key predates both of them: the tier arrived in 0.3.0
            // and the boolean before it. Those versions ran with the scan armed and no way to waive
            // it, so falling through to the field initializer silently upgrades such an operator
            // from "scan armed" to UNSAFE, which waives every denial there is. That is the exact
            // inversion the override=false migration below refuses to perform, reached by a
            // different route. A v2 config without the key is a different case and is left alone:
            // UNSAFE is genuinely its shipped default.
            // v0/v1 explicitly, NOT "anything that is not current". Written the other way, the
            // next schema bump would sweep every v2 config on disk into AUDITED -- and a v2 config
            // omits compatibilityTier precisely when the operator accepted the shipped default, so
            // that would silently convert the whole installed base into a mod measured at 0 of 187
            // eligible mob types.
            if (version <= 1 && !raw.has("compatibilityTier")) {
                raw.addProperty("compatibilityTier", CompatibilityTier.AUDITED.name());
                try {
                    dev.pathweaver.PathWeaver.LOG.warn("Your config predates the compatibility tier "
                        + "setting entirely. The versions that wrote it ran with the compatibility "
                        + "scan armed, so it has been migrated to compatibilityTier=AUDITED rather "
                        + "than inheriting today's UNSAFE default. On a heavily-modded pack expect "
                        + "PathWeaver to refuse; the world-start report names the mods responsible, "
                        + "and compatibilityTier=UNSAFE is the shipped default if you want it.");
                } catch (Throwable ignored) {
                    // Migrating must not depend on a logging backend being healthy.
                }
            }
            return;
        }
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

    /**
     * Turn a stored {@code repathToleranceBlocks: 0} into the 1 that 0.8.0 ships.
     *
     * <p>Zero meant the path reuse never ran, so the cheapest win this mod has was off unless
     * someone found the setting. It was zero because a retired {@code repathElisionEnabled} flag
     * defaulted true while this defaulted 0: the feature was advertised as working and was inert.
     * Changing the field initializer fixes that for new installs only, because a saved config wins
     * over a code default and Cloth writes every field, so every config file on disk carries a 0.
     * Without this, the fix reaches nobody who has ever opened the settings screen.
     *
     * <p>This is the uncomfortable part and it is worth stating rather than burying: a stored 0 is
     * indistinguishable from a deliberate 0, because the shipped default was 0 and the file records
     * the value rather than where it came from. Someone who chose to switch reuse off will have it
     * switched back on. That is why it is logged at WARN, names the setting, and says how to put it
     * back. The alternative was leaving a defect in place for every existing install to avoid
     * overriding a choice almost nobody made.
     *
     * <p>Only 0 moves. A config holding any other value recorded something the default never wrote,
     * so it is a real choice and is left exactly alone.
     */
    private static void migrateRepathTolerance(JsonObject raw, int version) {
        if (version >= PathWeaverConfig.CURRENT_CONFIG_VERSION) return;
        // One guard, not two. An absent key reads back as null and fails the type check below, so a
        // separate has() test could never fire -- mutation testing removed it and no assertion in the
        // suite noticed, which is the definition of a guard that is decoration.
        JsonElement element = raw.get("repathToleranceBlocks");
        if (!(element instanceof JsonPrimitive primitive) || !primitive.isNumber()) return;
        if (primitive.getAsBigDecimal().intValueExact() != 0) return;
        raw.addProperty("repathToleranceBlocks", 1);
        try {
            dev.pathweaver.PathWeaver.LOG.warn("Your config had repathToleranceBlocks=0, which meant "
                + "path reuse never ran at all. That was never a safety setting -- it was left over "
                + "from a retired flag, so the feature was advertised as working and did nothing. It "
                + "has been migrated to 1, the smallest value that does anything, and reuse still "
                + "only fires for a path that is still valid, still reaching, unfinished and not "
                + "invalidated. If you set 0 on purpose, set it again and it will be respected.");
        } catch (Throwable ignored) {
            // Migrating must not depend on a logging backend being healthy.
        }
    }

    /** The constants come from the field's own type, so a second enum setting is covered by adding it. */
    private static void strictOptionalEnum(JsonObject raw, String key, Class<?> type) {
        if (!raw.has(key)) return;
        JsonElement element = raw.get(key);
        if (!(element instanceof JsonPrimitive primitive) || !primitive.isString()) {
            throw new IllegalArgumentException(key + " must be a string");
        }
        String value = primitive.getAsString();
        for (Object constant : type.getEnumConstants()) {
            if (((Enum<?>) constant).name().equals(value)) return;
        }
        throw new IllegalArgumentException(key + " is not a known " + type.getSimpleName() + ": " + value);
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

    /**
     * A settings file could not be read or written.
     *
     * <p>Checked, deliberately. Losing a server owner's settings silently is the failure this whole
     * class is shaped around, and an unchecked exception here would let a caller drop it by accident.
     */
    public static final class ConfigIoException extends Exception {
        public ConfigIoException(Throwable cause) {
            super(cause);
        }
    }
}
