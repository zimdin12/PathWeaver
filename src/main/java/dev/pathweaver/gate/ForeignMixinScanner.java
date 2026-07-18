package dev.pathweaver.gate;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.pathweaver.PathWeaver;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.world.level.pathfinder.AmphibiousNodeEvaluator;
import net.minecraft.world.level.pathfinder.FlyNodeEvaluator;
import net.minecraft.world.level.pathfinder.SwimNodeEvaluator;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.extensibility.IMixinConfig;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;


/**
 * At startup, detects whether any OTHER mod mixes into one of our allowlisted vanilla evaluator
 * classes (or {@code PathFinder}). Such a mixin keeps the class identity intact, so the exact-class
 * allowlist cannot see it. Recognized untrusted evaluator hits are added to
 * {@link SafetyGate#deniedBySafety}, forcing that evaluator family back to synchronous pathing.
 *
 * The v0.2.0 scanner is fail-closed: metadata, ownership, active-config, or plugin-discovery errors
 * deny every otherwise eligible evaluator family. Compatibility exemptions, if any are added, are
 * exact audited mod-version/config/mixin-class/target tuples rather than owner prefixes.
 */
public final class ForeignMixinScanner {

    private static final Map<String, Class<?>> ALLOWLISTED_BY_NAME = Map.of(
        WalkNodeEvaluator.class.getName(), WalkNodeEvaluator.class,
        SwimNodeEvaluator.class.getName(), SwimNodeEvaluator.class,
        AmphibiousNodeEvaluator.class.getName(), AmphibiousNodeEvaluator.class,
        FlyNodeEvaluator.class.getName(), FlyNodeEvaluator.class
    );
    private static final String PATHFINDER = "net.minecraft.world.level.pathfinder.PathFinder";
    private static final Set<String> SHARED_PATHFINDING_TARGETS = Set.of(
        "net.minecraft.world.level.pathfinder.NodeEvaluator",
        "net.minecraft.world.level.pathfinder.PathfindingContext",
        "net.minecraft.world.entity.ai.navigation.PathNavigation",
        "net.minecraft.world.entity.ai.navigation.GroundPathNavigation",
        PATHFINDER
    );
    private static final Set<Class<?>> ELIGIBLE_EVALUATORS =
        Set.of(WalkNodeEvaluator.class, SwimNodeEvaluator.class);

    /**
     * Compatibility exemptions are exact audited tuples, never owner prefixes or whole-mod trust.
     * The initial fail-closed policy deliberately starts empty; entries require retained evidence for
     * one exact mod version, config, concrete mixin class, and target before they can be added.
     */
    private static final Set<AuditKey> AUDITED_EXEMPTIONS = Set.of();

    private ForeignMixinScanner() {}

    public static boolean isAuditedExemption(String modId, String version, String config,
                                             String mixinClass, String target) {
        return AUDITED_EXEMPTIONS.contains(
            new AuditKey(modId, version, config, mixinClass, target));
    }

    /** Pure, testable: map fully-qualified mixin target names to the allowlisted classes they hit. */
    public static Set<Class<?>> targetsTouchingAllowlist(Collection<String> targetClassNames) {
        Set<Class<?>> hits = new HashSet<>();
        for (String t : targetClassNames) {
            Class<?> c = ALLOWLISTED_BY_NAME.get(t);
            if (c != null) hits.add(c);
        }
        return hits;
    }

    /** Map sensitive mixin targets to the evaluator families whose async eligibility they invalidate. */
    public static Set<Class<?>> denialsForTargets(Collection<String> targetClassNames) {
        Set<Class<?>> denied = targetsTouchingAllowlist(targetClassNames);
        if (targetClassNames.stream().anyMatch(SHARED_PATHFINDING_TARGETS::contains)) {
            denied.addAll(ELIGIBLE_EVALUATORS);
        }
        return denied;
    }

    /** Pure fail-closed decision layer used by startup scanning and unit tests. */
    public static ScanDecision decide(Collection<ActiveConfig> configs, Collection<String> failures) {
        Set<Class<?>> denied = new HashSet<>();
        List<String> diagnostics = new ArrayList<>(failures);
        for (ActiveConfig config : configs) {
            for (TargetClaim claim : config.claims()) {
                if (!isAuditedExemption(config.modId(), config.version(), config.configName(),
                        claim.mixinClass(), claim.target())) {
                    denied.addAll(denialsForTargets(List.of(claim.target())));
                }
            }
        }
        if (!failures.isEmpty()) denied.addAll(ELIGIBLE_EVALUATORS);
        return new ScanDecision(Set.copyOf(denied), configs.size(), failures.size(),
            List.copyOf(diagnostics));
    }

    /** Read server-applicable mixin config names from Fabric metadata's string or object forms. */
    public static List<String> readServerMixinConfigNames(JsonObject metadata) {
        if (!metadata.has("mixins")) return List.of();
        if (!metadata.get("mixins").isJsonArray()) {
            throw new IllegalArgumentException("fabric.mod.json mixins must be an array");
        }
        List<String> configs = new ArrayList<>();
        for (JsonElement element : metadata.getAsJsonArray("mixins")) {
            if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
                configs.add(element.getAsString());
            } else if (element.isJsonObject()) {
                JsonObject entry = element.getAsJsonObject();
                if (!entry.has("config") || !entry.get("config").isJsonPrimitive()
                        || !entry.getAsJsonPrimitive("config").isString()) {
                    throw new IllegalArgumentException("Fabric mixin object requires a string config");
                }
                if (entry.has("environment") && (!entry.get("environment").isJsonPrimitive()
                        || !entry.getAsJsonPrimitive("environment").isString())) {
                    throw new IllegalArgumentException("Fabric mixin environment must be a string");
                }
                String environment = entry.has("environment")
                    ? entry.get("environment").getAsString() : "*";
                if (environment.equals("*") || environment.equals("server")) {
                    configs.add(entry.get("config").getAsString());
                } else if (!environment.equals("client")) {
                    throw new IllegalArgumentException("Unknown Fabric mixin environment: " + environment);
                }
            } else {
                throw new IllegalArgumentException("Fabric mixin entry must be a string or object");
            }
        }
        return List.copyOf(configs);
    }

    public record TargetClaim(String mixinClass, String target) {}

    public record ActiveConfig(String modId, String version, String configName,
                               Set<TargetClaim> claims, boolean pluginContributed) {
        public ActiveConfig {
            claims = Set.copyOf(claims);
        }

        public Set<String> targets() {
            Set<String> targets = new HashSet<>();
            for (TargetClaim claim : claims) targets.add(claim.target());
            return Set.copyOf(targets);
        }
    }

    public record ScanDecision(Set<Class<?>> denied, int scanned, int failed,
                               List<String> diagnostics) {}

    public record ScanReport(ScanDecision decision, List<ActiveConfig> configs) {
        public ScanReport {
            configs = List.copyOf(configs);
        }
    }

    private static volatile ScanReport lastScanReport = new ScanReport(
        new ScanDecision(Set.copyOf(ELIGIBLE_EVALUATORS), 0, 1,
            List.of("foreign-mixin scan has not completed")), List.of());

    public static ScanReport lastScanReport() {
        return lastScanReport;
    }

    private record DeclaredConfig(String modId, String version, String configName) {}
    private record AuditKey(String modId, String version, String config,
                            String mixinClass, String target) {}

    /**
     * Mixin 0.8.7 removes selected configs from Mixins.getConfigs(); inspect the active transformer's
     * prepared config list instead. Reflection drift is intentionally surfaced to the caller, which
     * fails closed rather than treating an empty list as proof of no foreign mixins.
     */
    private static List<IMixinConfig> activeMixinConfigs() throws ReflectiveOperationException {
        Object transformer = MixinEnvironment.getCurrentEnvironment().getActiveTransformer();
        var processorField = transformer.getClass().getDeclaredField("processor");
        processorField.setAccessible(true);
        Object processor = processorField.get(transformer);
        var configsField = processor.getClass().getDeclaredField("configs");
        configsField.setAccessible(true);
        Object raw = configsField.get(processor);
        if (!(raw instanceof Collection<?> configs)) {
            throw new IllegalStateException("Mixin processor configs field is not a collection");
        }
        List<IMixinConfig> result = new ArrayList<>();
        for (Object config : configs) {
            if (!(config instanceof IMixinConfig typed)) {
                throw new IllegalStateException("active Mixin config does not implement IMixinConfig");
            }
            result.add(typed);
        }
        if (result.isEmpty()) {
            throw new IllegalStateException("active Mixin config list is unexpectedly empty");
        }
        return List.copyOf(result);
    }

    private static Set<TargetClaim> preparedClaims(IMixinConfig config)
            throws ReflectiveOperationException {
        var getMixinsFor = config.getClass().getDeclaredMethod("getMixinsFor", String.class);
        getMixinsFor.setAccessible(true);
        Set<TargetClaim> claims = new HashSet<>();
        for (String target : config.getTargets()) {
            Object raw = getMixinsFor.invoke(config, target);
            if (!(raw instanceof Collection<?> mixins) || mixins.isEmpty()) {
                throw new IllegalStateException("prepared target has no concrete mixin identity: "
                    + config.getName() + " -> " + target);
            }
            for (Object mixin : mixins) {
                if (!(mixin instanceof IMixinInfo info)) {
                    throw new IllegalStateException("prepared mixin does not implement IMixinInfo");
                }
                claims.add(new TargetClaim(info.getClassName(), target));
            }
        }
        return Set.copyOf(claims);
    }

    /**
     * Read the target class names declared by {@code @Mixin} on a mixin class, from its bytecode.
     * Handles both {@code @Mixin(SomeClass.class)} (value = Type[]) and
     * {@code @Mixin(targets = "a.b.C")} (targets = String[]). No class loading/initialization.
     */
    public static List<String> readMixinAnnotationTargets(byte[] classBytes) {
        List<String> out = new ArrayList<>();
        ClassReader cr = new ClassReader(classBytes);
        cr.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                if (!"Lorg/spongepowered/asm/mixin/Mixin;".equals(desc)) return null;
                return new AnnotationVisitor(Opcodes.ASM9) {
                    @Override public AnnotationVisitor visitArray(String name) {
                        // name is "value" (Type[]) or "targets" (String[])
                        return new AnnotationVisitor(Opcodes.ASM9) {
                            @Override public void visit(String n, Object value) {
                                if (value instanceof Type t) out.add(t.getClassName());
                                else if (value instanceof String s) out.add(s);
                            }
                        };
                    }
                };
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return out;
    }

    /**
     * Scan every resolved Fabric mod container and every active Mixin configuration. Fabric Loader
     * expands jar-in-jar candidates into their own containers; active config targets include plugin
     * contributions returned by IMixinConfigPlugin.getMixins(). Any discovery ambiguity fails closed.
     */
    public static void scanAndPopulate() {
        Map<String, DeclaredConfig> owners = new HashMap<>();
        List<String> failures = new ArrayList<>();
        try {
            for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
                var metadata = mod.getMetadata();
                String id = metadata.getId();
                if (!"fabric".equals(metadata.getType())) continue;
                String version = metadata.getVersion().getFriendlyString();
                boolean foundMetadata = false;
                for (Path root : mod.getRootPaths()) {
                    Path metadataPath = root.resolve("fabric.mod.json");
                    if (!Files.exists(metadataPath)) continue;
                    foundMetadata = true;
                    try (var in = Files.newInputStream(metadataPath)) {
                        JsonObject json = JsonParser.parseReader(new InputStreamReader(in)).getAsJsonObject();
                        for (String configName : readServerMixinConfigNames(json)) {
                            DeclaredConfig owner = new DeclaredConfig(id, version, configName);
                            DeclaredConfig prior = owners.putIfAbsent(configName, owner);
                            if (prior != null && !prior.equals(owner)) {
                                failures.add("mixin config '" + configName + "' claimed by both "
                                    + prior.modId() + " and " + id);
                            }
                        }
                    } catch (Throwable t) {
                        failures.add("metadata read failed for " + id + ": " + t);
                    }
                }
                if (!foundMetadata) {
                    failures.add("fabric.mod.json not readable for loaded mod " + id);
                }
            }
        } catch (Throwable t) {
            failures.add("loaded-mod metadata discovery aborted: " + t);
        }

        List<ActiveConfig> active = new ArrayList<>();
        Set<String> preparedNames = new HashSet<>();
        try {
            for (IMixinConfig config : activeMixinConfigs()) {
                String name = config.getName();
                preparedNames.add(name);
                DeclaredConfig owner = owners.get(name);
                if (owner == null) {
                    failures.add("active mixin config has no unique Fabric owner: " + name);
                    continue;
                }
                Set<TargetClaim> claims = preparedClaims(config);
                if (PathWeaver.MOD_ID.equals(owner.modId())) continue;
                active.add(new ActiveConfig(owner.modId(), owner.version(), name, claims,
                    config.getPlugin() != null));
            }
        } catch (Throwable t) {
            failures.add("active Mixin configuration discovery failed: " + t);
        }

        for (DeclaredConfig declared : owners.values()) {
            if (!preparedNames.contains(declared.configName())) {
                failures.add("declared server mixin config not prepared: " + declared.modId()
                    + ":" + declared.configName());
            }
        }

        ScanDecision decision = decide(active, failures);
        lastScanReport = new ScanReport(decision, active);
        SafetyGate.deniedBySafety.clear();
        SafetyGate.deniedBySafety.addAll(decision.denied());
        for (ActiveConfig config : active) {
            Set<Class<?>> denied = denialsForTargets(config.targets());
            if (!denied.isEmpty()) {
                PathWeaver.LOG.warn("Mod '{}' config '{}' targets sensitive pathfinding code{}; "
                        + "forcing {} to sync pathing.",
                    config.modId(), config.configName(),
                    config.pluginContributed() ? " (plugin-expanded)" : "", denied);
            }
        }
        for (String failure : decision.diagnostics()) {
            PathWeaver.LOG.warn("Foreign-mixin scan failure (fail-closed): {}", failure);
        }
        PathWeaver.LOG.info("Foreign-mixin scan complete: scanned={}, failed={}, deniedFamilies={}.",
            decision.scanned(), decision.failed(), SafetyGate.deniedBySafety.size());
    }
}
