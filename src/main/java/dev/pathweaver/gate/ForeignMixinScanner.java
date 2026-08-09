package dev.pathweaver.gate;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.pathweaver.PathWeaver;
import dev.pathweaver.config.CompatibilityTier;
import dev.pathweaver.config.PathWeaverConfig;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.world.level.pathfinder.SwimNodeEvaluator;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.extensibility.IMixinConfig;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.io.InputStream;
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
 * The v0.2.1 scanner is fail-closed: metadata, ownership, active-config, or plugin-discovery errors
 * deny every otherwise eligible evaluator family. Compatibility exemptions, if any are added, are
 * exact audited mod-version/config/mixin-class/target tuples rather than owner prefixes.
 */
public final class ForeignMixinScanner {

    /**
     * Derived from {@link SafetyGate#allowlisted()} rather than restated here.
     *
     * <p>These were two independent lists of the same thing, which is a defect waiting for the next
     * evaluator to be added: widening the gate without widening the scanner would leave a family
     * eligible that no foreign mixin could ever deny.
     */
    private static final Map<String, Class<?>> ALLOWLISTED_BY_NAME = allowlistByName();

    private static Map<String, Class<?>> allowlistByName() {
        Map<String, Class<?>> byName = new HashMap<>();
        for (Class<?> allowed : SafetyGate.allowlisted()) byName.put(allowed.getName(), allowed);
        return Map.copyOf(byName);
    }
    private static final String PATHFINDER = "net.minecraft.world.level.pathfinder.PathFinder";
    /**
     * Classes a worker thread reads or mutates during an async search. A foreign mixin into any of
     * them invalidates every eligible evaluator, because the worker would then run modified code
     * whose thread-safety we cannot audit.
     *
     * <p><strong>This list is INCOMPLETE, and known to be.</strong> It used to claim it "must cover
     * the whole worker-reachable surface"; it does not, and an enumeration maintained by hand cannot,
     * so the claim was removed rather than the gap quietly left under it.
     *
     * <p>What is missing is the tail of the block read. {@code PathNavigationRegion} is listed, and
     * {@code getBlockState} does not stop there — it continues into {@code LevelChunk},
     * {@code LevelChunkSection} and {@code PalettedContainer}, none of which are here. On an ordinary
     * performance pack that is not hypothetical: Lithium {@code @Overwrite}s
     * {@code LevelChunk.getBlockState}, Lithium and FerriteCore both replace
     * {@code PalettedContainer}'s threading detector, ScalableLux mixes into {@code LevelChunk} and
     * {@code ChunkAccess}, and ServerCore mixes into {@code BlockGetter} — the last through a config
     * whose bytes this project already pins in {@code AuditedMixinCompatibility}, so the audit read
     * the file and looked past the target.
     *
     * <p>Adding those classes was tried and is a product decision, not a bug fix: it makes
     * {@code AUDITED} deny every family on any pack containing Lithium, because Lithium's chunk
     * mixins are outside its pinned pathfinding exemption. That is the gate working correctly and it
     * would take the audited tier from "usually refuses" to "always refuses" until those mixins are
     * audited too. It is recorded here and in COMPATIBILITY.md rather than half-done.
     *
     * <p>The shipped default waives this list wholesale, so the gap changes nothing out of the box.
     * It matters only for an operator who chose {@code AUDITED} believing it covers the whole read
     * path — which is exactly why the completeness claim had to go.
     *
     * <p>The search-scratch types are listed for a different reason: a mixin that adds shared state
     * to {@code BinaryHeap}, {@code Node}, {@code Path} or {@code Target} breaks the per-search
     * isolation the design depends on.
     */
    private static final Set<String> SHARED_PATHFINDING_TARGETS = Set.of(
        "net.minecraft.world.level.pathfinder.NodeEvaluator",
        "net.minecraft.world.level.pathfinder.PathfindingContext",
        "net.minecraft.world.entity.ai.navigation.PathNavigation",
        "net.minecraft.world.entity.ai.navigation.GroundPathNavigation",
        PATHFINDER,
        // World view handed to the worker, and the block state it reads through.
        "net.minecraft.world.level.PathNavigationRegion",
        "net.minecraft.world.level.block.state.BlockBehaviour$BlockStateBase",
        "net.minecraft.world.level.pathfinder.PathTypeCache",
        // Navigations that own an eligible evaluator; GroundPathNavigation alone was not enough.
        "net.minecraft.world.entity.ai.navigation.WaterBoundPathNavigation",
        "net.minecraft.world.entity.ai.navigation.AmphibiousPathNavigation",
        "net.minecraft.world.entity.ai.navigation.FlyingPathNavigation",
        // Per-search scratch structures that must stay confined to the searching thread.
        "net.minecraft.world.level.pathfinder.BinaryHeap",
        "net.minecraft.world.level.pathfinder.Node",
        "net.minecraft.world.level.pathfinder.Path",
        "net.minecraft.world.level.pathfinder.Target"
    );
    private static final Set<Class<?>> ELIGIBLE_EVALUATORS = SafetyGate.allowlisted();

    /**
     * Compatibility exemptions are exact audited tuples, never owner prefixes or whole-mod trust.
     * The initial fail-closed policy deliberately starts empty; entries require retained evidence for
     * one exact mod version, config, concrete mixin class, and target before they can be added.
     */
    private static final String FABRIC_CONTENT_ID = "fabric-content-registries-v0";
    private static final String FABRIC_CONTENT_VERSION = "11.2.1+76b0b6bb4c";
    private static final String FABRIC_CONTENT_CONFIG = "fabric-content-registries-v0.mixins.json";
    private static final String FABRIC_CONTEXT_MIXIN =
        "net.fabricmc.fabric.mixin.content.registry.PathfindingContextMixin";
    private static final String FABRIC_WALK_MIXIN =
        "net.fabricmc.fabric.mixin.content.registry.WalkNodeEvaluatorMixin";
    private static final String FABRIC_BLOCK_STATE_BASE_MIXIN =
        "net.fabricmc.fabric.mixin.content.registry.BlockBehaviourBlockStateBaseMixin";
    private static final String PATHFINDING_CONTEXT =
        "net.minecraft.world.level.pathfinder.PathfindingContext";
    private static final String WALK_EVALUATOR =
        "net.minecraft.world.level.pathfinder.WalkNodeEvaluator";
    private static final String BLOCK_STATE_BASE =
        "net.minecraft.world.level.block.state.BlockBehaviour$BlockStateBase";
    private static final TargetClaim EXACT_FABRIC_CONTEXT_CLAIM =
        new TargetClaim(FABRIC_CONTEXT_MIXIN, PATHFINDING_CONTEXT);
    private static final TargetClaim EXACT_FABRIC_WALK_CLAIM =
        new TargetClaim(FABRIC_WALK_MIXIN, WALK_EVALUATOR);
    private static final TargetClaim EXACT_FABRIC_BLOCK_STATE_BASE_CLAIM =
        new TargetClaim(FABRIC_BLOCK_STATE_BASE_MIXIN, BLOCK_STATE_BASE);

    private static final Set<AuditKey> AUDITED_EXEMPTIONS = Set.of(
        new AuditKey(FABRIC_CONTENT_ID, FABRIC_CONTENT_VERSION, FABRIC_CONTENT_CONFIG,
            FABRIC_CONTEXT_MIXIN, PATHFINDING_CONTEXT, null),
        new AuditKey(FABRIC_CONTENT_ID, FABRIC_CONTENT_VERSION, FABRIC_CONTENT_CONFIG,
            FABRIC_WALK_MIXIN, WALK_EVALUATOR, null),
        new AuditKey(FABRIC_CONTENT_ID, FABRIC_CONTENT_VERSION, FABRIC_CONTENT_CONFIG,
            FABRIC_BLOCK_STATE_BASE_MIXIN, BLOCK_STATE_BASE, null),
        new AuditKey(FabricInteractionCompatibility.MOD_ID,
            FabricInteractionCompatibility.MOD_VERSION,
            FabricInteractionCompatibility.CONFIG,
            FabricInteractionCompatibility.MIXIN,
            FabricInteractionCompatibility.TARGET, null),
        new AuditKey(AuditedMixinCompatibility.SERVERCORE_ID,
            AuditedMixinCompatibility.SERVERCORE_VERSION,
            AuditedMixinCompatibility.SERVERCORE_CONFIG,
            AuditedMixinCompatibility.SERVERCORE_MIXIN,
            AuditedMixinCompatibility.PATH_FINDER,
            new PluginIdentity(AuditedMixinCompatibility.SERVERCORE_PLUGIN,
                AuditedMixinCompatibility.SERVERCORE_PLUGIN_SHA)),
        new AuditKey(AuditedMixinCompatibility.RABBIT_ID,
            AuditedMixinCompatibility.RABBIT_VERSION,
            AuditedMixinCompatibility.RABBIT_CONFIG,
            AuditedMixinCompatibility.RABBIT_MIXIN,
            AuditedMixinCompatibility.PATH_NAVIGATION, null)
    );

    private ForeignMixinScanner() {}

    public static boolean isAuditedExemption(String modId, String version, String config,
                                             String mixinClass, String target) {
        return AUDITED_EXEMPTIONS.stream().anyMatch(key -> key.modId().equals(modId)
            && key.version().equals(version) && key.config().equals(config)
            && key.mixinClass().equals(mixinClass) && key.target().equals(target));
    }

    /**
     * The scan could not complete, so nothing can be waived.
     *
     * <p>Shared because it was spelled out three times — once directly beneath a comment explaining
     * that open-coding a rule is the drift a shared predicate exists to prevent. The comment had been
     * moved and the copy written under it in the same commit.
     */
    public static boolean scanFailed() {
        return scanFailed(lastScanReport().decision());
    }

    /** Test seam: the same question against a decision built by {@link #decide}, not the live one. */
    static boolean scanFailed(ScanDecision decision) {
        return decision.failed() > 0;
    }

    /** Pure, testable: map fully-qualified mixin target names to the allowlisted classes they hit. */

    public static Set<Class<?>> targetsTouchingAllowlist(Collection<String> targetClassNames) {
        Set<Class<?>> hits = new HashSet<>();
        for (String t : targetClassNames) {
            Class<?> c = ALLOWLISTED_BY_NAME.get(normalizeTargetName(t));
            if (c != null) hits.add(c);
        }
        return hits;
    }

    /** Map sensitive mixin targets to the evaluator families whose async eligibility they invalidate. */
    public static Set<Class<?>> denialsForTargets(Collection<String> targetClassNames) {
        Set<Class<?>> denied = targetsTouchingAllowlist(targetClassNames);
        if (targetClassNames.stream().map(ForeignMixinScanner::normalizeTargetName)
                .anyMatch(SHARED_PATHFINDING_TARGETS::contains)) {
            denied.addAll(ELIGIBLE_EVALUATORS);
        }
        return denied;
    }

    private static String normalizeTargetName(String target) {
        return target.replace('/', '.');
    }

    /**
     * Decide whether an active Mixin configuration that no Fabric mod declares must fail closed.
     *
     * <p>Fabric metadata is the only ownership evidence available, so a config missing from every
     * {@code fabric.mod.json} cannot be audited and normally denies everything. The single
     * exception is a config that transforms <em>nothing at all</em>: some mods register an
     * undeclared parent config whose mixin list is empty. That cannot influence any code, so it
     * must not veto the feature.
     *
     * <p>The test is deliberately "claims nothing", not "claims nothing I recognise as sensitive".
     * The sensitive-target list is a best-effort enumeration and is known to be incomplete, so
     * judging an unauditable config against it would silently admit mixins into pathfinding
     * internals that the list happens to omit. A config carrying an {@link IMixinConfigPlugin}
     * also always fails closed: a plugin can rewrite classes belonging to <em>other</em> configs
     * while owning no targets of its own, so an empty claim set proves nothing about it.
     *
     * @return the failure to record, or {@code null} only when the config declares no mixins and
     *     carries no plugin.
     */
    static String unattributableConfigFailure(String configName, Set<TargetClaim> claims,
                                              boolean pluginContributed) {
        if (claims.isEmpty() && !pluginContributed) return null;
        return "active mixin config has no unique Fabric owner: " + configName;
    }

    /** Pure fail-closed decision layer used by startup scanning and unit tests. */
    public static ScanDecision decide(Collection<ActiveConfig> configs, Collection<String> failures) {
        return decide(configs, failures,
            SwimExemptionEvidence.unverified("exact Swim runtime fingerprint not supplied"),
            AuditedExemptionEvidence.unverified());
    }

    /** Pure decision layer with an explicit runtime fingerprint witness. */
    public static ScanDecision decide(Collection<ActiveConfig> configs, Collection<String> failures,
                                      SwimExemptionEvidence swimEvidence) {
        return decide(configs, failures, swimEvidence, AuditedExemptionEvidence.unverified());
    }

    /** Pure decision layer with exact runtime witnesses for all audited foreign tuples. */
    public static ScanDecision decide(Collection<ActiveConfig> configs, Collection<String> failures,
                                      SwimExemptionEvidence swimEvidence,
                                      AuditedExemptionEvidence auditedEvidence) {
        return decide(configs, failures, swimEvidence, auditedEvidence, Set.of());
    }

    /**
     * @param trustedMods mod ids the operator has chosen to run unaudited, scoped to those mods
     */
    public static ScanDecision decide(Collection<ActiveConfig> configs, Collection<String> failures,
                                      SwimExemptionEvidence swimEvidence,
                                      AuditedExemptionEvidence auditedEvidence,
                                      Set<String> trustedMods) {
        Set<Class<?>> denied = new HashSet<>();
        List<String> diagnostics = new ArrayList<>(failures);
        diagnostics.addAll(swimEvidence.diagnostics());
        diagnostics.addAll(auditedEvidence.diagnostics());
        for (ActiveConfig config : configs) {
            // Passed in rather than read from a static, so a test's decision depends only on its
            // arguments. The production path hands in the set frozen at scan time.
            if (trustedMods.contains(config.modId())) continue;
            boolean exactSwimShape = exactFabricSwimClaimShape(config, swimEvidence);
            denied.addAll(denialsForConfig(config, exactSwimShape, auditedEvidence));
        }
        if (!failures.isEmpty()) denied.addAll(ELIGIBLE_EVALUATORS);
        return new ScanDecision(Set.copyOf(denied), configs.size(), failures.size(),
            List.copyOf(diagnostics));
    }

    private static Set<Class<?>> denialsForConfig(ActiveConfig config, boolean exactSwimShape,
                                                   AuditedExemptionEvidence auditedEvidence) {
        Set<Class<?>> denied = new HashSet<>();
        for (TargetClaim claim : config.claims()) {
            if (exactSwimShape && (claim.equals(EXACT_FABRIC_CONTEXT_CLAIM)
                    || claim.equals(EXACT_FABRIC_BLOCK_STATE_BASE_CLAIM))) {
                // The context handler modifies only getPathTypeFromState, which exact Swim never
                // reaches. The BlockStateBase mixin is structural only: the pinned artifact adds
                // one refresher interface/method and modifies no worker-read method. The separate
                // Walk claim remains non-exempt and must deny Walk.
                continue;
            }
            AuditKey key = new AuditKey(config.modId(), config.version(), config.configName(),
                claim.mixinClass(), normalizeTargetName(claim.target()), config.pluginIdentity());
            if (auditedEvidence.verified().contains(key)) continue;
            denied.addAll(denialsForTargets(List.of(claim.target())));
        }
        return Set.copyOf(denied);
    }

    static boolean isForeignModId(String modId) {
        return !PathWeaver.MOD_ID.equals(modId);
    }

    private static boolean exactFabricSwimClaimShape(ActiveConfig config,
                                                      SwimExemptionEvidence evidence) {
        if (!evidence.verified() || config.pluginContributed()
                || !FABRIC_CONTENT_ID.equals(config.modId())
                || !FABRIC_CONTENT_VERSION.equals(config.version())
                || !FABRIC_CONTENT_CONFIG.equals(config.configName())
                || !config.claims().contains(EXACT_FABRIC_CONTEXT_CLAIM)
                || !config.claims().contains(EXACT_FABRIC_WALK_CLAIM)
                || !config.claims().contains(EXACT_FABRIC_BLOCK_STATE_BASE_CLAIM)) {
            return false;
        }
        for (TargetClaim claim : config.claims()) {
            String target = normalizeTargetName(claim.target());
            boolean sensitive = ALLOWLISTED_BY_NAME.containsKey(target)
                || SHARED_PATHFINDING_TARGETS.contains(target);
            if (sensitive && !claim.equals(EXACT_FABRIC_CONTEXT_CLAIM)
                    && !claim.equals(EXACT_FABRIC_WALK_CLAIM)
                    && !claim.equals(EXACT_FABRIC_BLOCK_STATE_BASE_CLAIM)) {
                return false;
            }
        }
        return true;
    }

    /** Read server-applicable mixin config names from Fabric metadata's string or object forms. */
    public static List<String> readServerMixinConfigNames(JsonObject metadata) {
        return readEnvironmentMixinConfigNames(metadata, "server");
    }

    /** Read client-applicable names; integrated servers share these transformed classes. */
    public static List<String> readClientMixinConfigNames(JsonObject metadata) {
        return readEnvironmentMixinConfigNames(metadata, "client");
    }

    private static List<String> readEnvironmentMixinConfigNames(
            JsonObject metadata, String runtimeEnvironment) {
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
                if (environment.equals("*") || environment.equals(runtimeEnvironment)) {
                    configs.add(entry.get("config").getAsString());
                } else if (!environment.equals("client") && !environment.equals("server")) {
                    throw new IllegalArgumentException("Unknown Fabric mixin environment: " + environment);
                }
            } else {
                throw new IllegalArgumentException("Fabric mixin entry must be a string or object");
            }
        }
        return List.copyOf(configs);
    }

    public record TargetClaim(String mixinClass, String target) {}

    /** Exact prepared-plugin identity; both class name and loaded class bytes are authenticated. */
    public record PluginIdentity(String className, String classSha256) {}

    public record ActiveConfig(String modId, String version, String configName,
                               Set<TargetClaim> claims, PluginIdentity pluginIdentity) {
        public ActiveConfig {
            claims = Set.copyOf(claims);
        }

        /** Compatibility constructor for generic scanner tests; never matches an exact plugin proof. */
        public ActiveConfig(String modId, String version, String configName,
                            Set<TargetClaim> claims, boolean pluginContributed) {
            this(modId, version, configName, claims, pluginContributed
                ? new PluginIdentity("<unidentified-plugin>", "<unverified>") : null);
        }

        public boolean pluginContributed() {
            return pluginIdentity != null;
        }

        public Set<String> targets() {
            Set<String> targets = new HashSet<>();
            for (TargetClaim claim : claims) targets.add(claim.target());
            return Set.copyOf(targets);
        }
    }

    public record ScanDecision(Set<Class<?>> denied, int scanned, int failed,
                               List<String> diagnostics) {}

    /** Runtime proof for the exact Fabric/vanilla bytecode tuple; false is always fail-closed. */
    public record SwimExemptionEvidence(boolean verified, List<String> diagnostics) {
        public SwimExemptionEvidence {
            diagnostics = List.copyOf(diagnostics);
        }

        public static SwimExemptionEvidence unverified(String reason) {
            return new SwimExemptionEvidence(false, List.of(reason));
        }
    }

    public record ScanReport(ScanDecision decision, List<ActiveConfig> configs,
                             AuditedExemptionEvidence auditedEvidence) {
        public ScanReport {
            configs = List.copyOf(configs);
        }

        public ScanReport(ScanDecision decision, List<ActiveConfig> configs) {
            this(decision, configs, AuditedExemptionEvidence.unverified());
        }
    }

    private static volatile ScanReport lastScanReport = new ScanReport(
        new ScanDecision(Set.copyOf(ELIGIBLE_EVALUATORS), 0, 1,
            List.of("foreign-mixin scan has not completed")), List.of());

    /**
     * True once a live scan has published a report. Distinguishes "scan completed and attributed
     * nothing to this mod" from "no scan has run yet", which the retained report alone cannot,
     * because both present as an empty config list.
     */
    private static volatile boolean scanCompleted;

    // ---- frozen compatibility tier -------------------------------------------------------------
    //
    // Owned here rather than by the facade that reads it, because package-private is not a safety
    // boundary on Fabric. Runtime package access is package name plus classloader, and mods share
    // the target classloader, so a mod shipping a class in dev.pathweaver.gate could call a
    // package-private mutator directly. Publication happening before this scan runs would win under
    // first-write-wins and make the scan's own STRICT publication a no-op, so mutation is private to
    // the class that computes the value. Only reads are exposed.
    private static volatile boolean tierFrozen;
    private static volatile boolean tierAllowsAudited;
    private static volatile boolean tierBypassesScan;

    /**
     * Freeze the tier for the process. Private on purpose: the compiler enforces that the only
     * caller is the scan below, whatever else shares the package at runtime.
     */
    /**
     * Whether {@code compatibilityTier} is allowed to waive this decision's denials.
     *
     * <p>It is not, if the scan failed. {@code decide} turns any failure into a blanket denial of
     * every family, so clearing denials unconditionally also cleared "the scanner could not tell what
     * is installed" -- an unreadable {@code fabric.mod.json}, two mods claiming one config name,
     * Mixin internals drifting, a declared config that never prepared. Every fail-closed path in this
     * class terminated in ALLOW on a stock install once {@code UNSAFE} became the shipped default.
     *
     * <p>The tier waives what the scan <em>found</em>. It does not waive the scan being unable to
     * look. Those are different requests and only one of them was ever made.
     */
    static boolean tierMayWaiveDenials(ScanDecision decision) {
        return decision.failed() == 0;
    }

    private static synchronized void freezeActiveTier(boolean allowsAudited, boolean bypassesScan) {
        if (tierFrozen) return;
        tierAllowsAudited = allowsAudited;
        tierBypassesScan = bypassesScan;
        tierFrozen = true;                 // published last; readers gate on it
    }

    /** True when audited exemptions may be honoured. False until the scan freezes a tier. */
    public static boolean frozenTierAllowsAudited() {
        return tierFrozen && tierAllowsAudited;
    }

    /** True when the operator asked for no compatibility checking at all. */
    public static boolean frozenTierBypassesScan() {
        return tierFrozen && tierBypassesScan;
    }

    /** True once the scan has frozen a tier. */
    public static boolean tierFrozen() {
        return tierFrozen;
    }

    public static boolean scanCompleted() {
        return scanCompleted;
    }

    /**
     * True when some active foreign mixin config claims this class as a target.
     *
     * <p>Exists for audits that verify bytecode read from a mod's own jar. Those bytes are the
     * artifact, not what the JVM ends up running: another mod can transform the same class before it
     * is loaded, and a hash of the original file cannot see that. Fail-closed twice over — an
     * unfinished scan and an unparsed target both answer true.
     */
    public static boolean anyActiveClaimTargets(String className) {
        if (!scanCompleted) return true;
        String wanted = normalizeTargetName(className);
        for (ActiveConfig config : lastScanReport.configs()) {
            for (String target : config.targets()) {
                if (wanted.equals(normalizeTargetName(target))) return true;
            }
        }
        return false;
    }

    /**
     * Mod ids whose mixins caused a denial, in the order an operator should read them.
     *
     * <p>Collected while the per-config warnings are emitted. Those warnings appear during early
     * mixin scanning, hundreds of lines before a world loads, which is why nobody ever reads them —
     * the world-start verdict repeats the conclusion at the moment it becomes relevant.
     */
    public static List<String> blockingModIds() {
        return blockingModIds;
    }

    private static volatile List<String> blockingModIds = List.of();

    /** Mod ids the operator trusted that actually had a denial waived. */
    public static List<String> trustedModIdsInUse() {
        return trustedModIdsInUse;
    }

    private static volatile List<String> trustedModIdsInUse = List.of();

    /**
     * The operator's trust list, read once at scan time like the tier.
     *
     * <p>Fail-closed on any trouble reading it: an empty set denies, which is the safe direction.
     */
    private static Set<String> operatorTrustedMods() {
        try {
            List<String> configured = PathWeaverConfig.get().trustedMods;
            if (configured == null || configured.isEmpty()) return Set.of();
            Set<String> ids = new HashSet<>();
            for (String id : configured) {
                if (id != null && !id.isBlank()) ids.add(id.trim());
            }
            return Set.copyOf(ids);
        } catch (Throwable unreadable) {
            PathWeaver.LOG.warn("Could not read the trusted-mod list; treating it as empty.", unreadable);
            return Set.of();
        }
    }

    public static ScanReport lastScanReport() {
        return lastScanReport;
    }

    private record DeclaredConfig(String modId, String version, String configName) {}
    record AuditKey(String modId, String version, String config,
                    String mixinClass, String target, PluginIdentity pluginIdentity) {}

    /** Runtime proof set. A nominal audit tuple is never exempt until its exact key is present. */
    public record AuditedExemptionEvidence(Set<AuditKey> verified, List<String> diagnostics) {
        public AuditedExemptionEvidence {
            verified = Set.copyOf(verified);
            diagnostics = List.copyOf(diagnostics);
        }

        static AuditedExemptionEvidence verified(AuditKey key) {
            return new AuditedExemptionEvidence(Set.of(key), List.of());
        }

        /** Public non-vacuity witness for live tests and diagnostics without exposing AuditKey. */
        public boolean verifies(ActiveConfig config, TargetClaim claim) {
            return verified.contains(new AuditKey(config.modId(), config.version(),
                config.configName(), claim.mixinClass(), claim.target(),
                config.pluginIdentity()));
        }

        public static AuditedExemptionEvidence unverified() {
            return new AuditedExemptionEvidence(Set.of(), List.of());
        }

        public static AuditedExemptionEvidence unverified(String reason) {
            return new AuditedExemptionEvidence(Set.of(), List.of(reason));
        }

        AuditedExemptionEvidence merge(AuditedExemptionEvidence other) {
            Set<AuditKey> keys = new HashSet<>(verified);
            keys.addAll(other.verified);
            List<String> reasons = new ArrayList<>(diagnostics);
            reasons.addAll(other.diagnostics);
            return new AuditedExemptionEvidence(keys, reasons);
        }
    }

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

    static Set<TargetClaim> preparedClaims(IMixinConfig config)
            throws ReflectiveOperationException {
        var getMixinsFor = config.getClass().getDeclaredMethod("getMixinsFor", String.class);
        getMixinsFor.setAccessible(true);
        Set<TargetClaim> claims = new HashSet<>();
        for (String rawTarget : config.getTargets()) {
            Object raw = getMixinsFor.invoke(config, rawTarget);
            if (!(raw instanceof Collection<?> mixins) || mixins.isEmpty()) {
                throw new IllegalStateException("prepared target has no concrete mixin identity: "
                    + config.getName() + " -> " + rawTarget);
            }
            String target = normalizeTargetName(rawTarget);
            if (!target.equals(rawTarget)) {
                PathWeaver.LOG.warn("Mixin config '{}' supplied internal target name '{}'; "
                    + "normalizing to '{}' for fail-closed compatibility checks.",
                    config.getName(), rawTarget, target);
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

    static PluginIdentity preparedPluginIdentity(IMixinConfig config) throws java.io.IOException {
        Object plugin = config.getPlugin();
        if (plugin == null) return null;
        Class<?> type = plugin.getClass();
        String simpleResource = type.getName().substring(type.getPackageName().length() + 1)
            + ".class";
        try (InputStream in = type.getResourceAsStream(simpleResource)) {
            if (in == null) {
                throw new java.io.IOException("prepared plugin class resource missing: "
                    + type.getName());
            }
            return new PluginIdentity(type.getName(),
                AuditedMixinCompatibility.sha256(in.readAllBytes()));
        }
    }

    /**
     * Scan every resolved Fabric mod container and every active Mixin configuration. Fabric Loader
     * expands jar-in-jar candidates into their own containers; active config targets include plugin
     * contributions returned by IMixinConfigPlugin.getMixins(). Any discovery ambiguity fails closed.
     */
    public static void scanAndPopulate() {
        SafetyGate.denyAllEligible();
        Map<String, DeclaredConfig> owners = new HashMap<>();
        List<String> failures = new ArrayList<>();
        try {
            FabricLoader loader = FabricLoader.getInstance();
            boolean clientEnvironment = loader.getEnvironmentType() == EnvType.CLIENT;
            for (ModContainer mod : loader.getAllMods()) {
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
                        List<String> configNames = clientEnvironment
                            ? readClientMixinConfigNames(json)
                            : readServerMixinConfigNames(json);
                        for (String configName : configNames) {
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
                    // An unattributable config only matters if it can actually affect pathfinding.
                    // Some mods ship a parent/vestigial config that declares no mixins at all and is
                    // not listed in any fabric.mod.json (c2me.mixins.json is one). Treating those as
                    // scan failures disabled the whole feature over a config that transforms nothing.
                    // Still fail closed whenever the config cannot be inspected or does touch a
                    // sensitive target: unauditable *and* relevant remains a denial.
                    Set<TargetClaim> unownedClaims;
                    try {
                        unownedClaims = preparedClaims(config);
                    } catch (Throwable t) {
                        failures.add("unattributable mixin config could not be inspected: "
                            + name + ": " + t);
                        continue;
                    }
                    String unownedFailure = unattributableConfigFailure(name, unownedClaims,
                        config.getPlugin() != null);
                    if (unownedFailure != null) failures.add(unownedFailure);
                    continue;
                }
                Set<TargetClaim> claims = preparedClaims(config);
                if (!isForeignModId(owner.modId())) continue;
                active.add(new ActiveConfig(owner.modId(), owner.version(), name, claims,
                    preparedPluginIdentity(config)));
            }
        } catch (Throwable t) {
            failures.add("active Mixin configuration discovery failed: " + t);
        }

        for (DeclaredConfig declared : owners.values()) {
            if (!preparedNames.contains(declared.configName())) {
                failures.add("declared current-environment mixin config not prepared: " + declared.modId()
                    + ":" + declared.configName());
            }
        }

        SwimExemptionEvidence swimEvidence = new SwimExemptionEvidence(false, List.of());
        AuditedExemptionEvidence auditedEvidence = AuditedExemptionEvidence.unverified();
        FabricLandPathRegistryLatch.publishHooksVerified(false);
        try {
            FabricLoader loader = FabricLoader.getInstance();
            var module = loader.getModContainer(FABRIC_CONTENT_ID);
            if (module.isPresent()) {
                swimEvidence = FabricSwimCompatibility.inspectRuntime(loader, module.get());
                AuditedExemptionEvidence landEvidence =
                    FabricSwimCompatibility.inspectLandRuntime(loader, module.get());
                auditedEvidence = auditedEvidence.merge(landEvidence);
                boolean landVerified = landEvidence.verified().size() == 3;
                FabricLandPathRegistryLatch.publishHooksVerified(landVerified);
                if (swimEvidence.verified()) {
                    PathWeaver.LOG.info("Verified exact Fabric content-registry/vanilla Swim tuple.");
                }
                if (landVerified) {
                    PathWeaver.LOG.info("Verified exact Fabric land-registry lifecycle and hook targets; "
                        + "Walk may dispatch only while the monotonic registry latch remains empty.");
                }
            }
        } catch (Throwable t) {
            FabricLandPathRegistryLatch.publishHooksVerified(false);
            swimEvidence = SwimExemptionEvidence.unverified(
                "exact Fabric Swim runtime verification aborted: " + t);
        }

        try {
            FabricLoader loader = FabricLoader.getInstance();
            CompatibilityTier tier = PathWeaverConfig.get().compatibilityTier;
            // Freeze it here, where the evidence that depends on it is computed. Everything else
            // reads the frozen answer, so a later settings save cannot leave startup denials waived
            // while per-request checks tighten.
            freezeActiveTier(tier.allowsAudited(), tier.bypassesScan());
            for (String id : List.of(AuditedMixinCompatibility.SERVERCORE_ID,
                                     AuditedMixinCompatibility.RABBIT_ID,
                                     FabricInteractionCompatibility.MOD_ID,
                                     LithiumPathfindingCompatibility.MOD_ID,
                                     DiagonalBlocksCompatibility.MOD_ID)) {
                var module = loader.getModContainer(id);
                if (module.isEmpty()) continue;
                AuditedExemptionEvidence moduleEvidence;
                if (id.equals(FabricInteractionCompatibility.MOD_ID)) {
                    moduleEvidence = FabricInteractionCompatibility.inspectRuntime(
                        loader, module.get(), tier);
                } else if (id.equals(DiagonalBlocksCompatibility.MOD_ID)) {
                    moduleEvidence = DiagonalBlocksCompatibility.inspectRuntime(
                        loader, module.get(), tier);
                } else if (id.equals(LithiumPathfindingCompatibility.MOD_ID)) {
                    // Tier-gated: below AUDITED this deliberately yields no evidence, so Lithium's
                    // claims keep denying. The tier withholds proof rather than suppressing denial.
                    moduleEvidence = LithiumPathfindingCompatibility.inspectRuntime(
                        loader, module.get(), tier);
                } else {
                    moduleEvidence = AuditedMixinCompatibility.inspectRuntime(loader, module.get());
                }
                auditedEvidence = auditedEvidence.merge(moduleEvidence);
                if (!moduleEvidence.verified().isEmpty()) {
                    PathWeaver.LOG.info("Verified exact audited compatibility tuple for '{}'; "
                        + "only its pinned claim is exempt.", id);
                }
            }
        } catch (Throwable t) {
            auditedEvidence = auditedEvidence.merge(AuditedExemptionEvidence.unverified(
                "audited compatibility runtime verification aborted: " + t));
        }

        Set<String> trustedMods = operatorTrustedMods();
        ScanDecision decision = decide(active, failures, swimEvidence, auditedEvidence, trustedMods);
        lastScanReport = new ScanReport(decision, active, auditedEvidence);
        scanCompleted = true;
        SafetyGate.replaceDenials(decision.denied());
        java.util.SortedSet<String> blockers = new java.util.TreeSet<>();
        java.util.SortedSet<String> trustedInUse = new java.util.TreeSet<>();
        for (ActiveConfig config : active) {
            Set<Class<?>> denied = denialsForConfig(config,
                exactFabricSwimClaimShape(config, swimEvidence), auditedEvidence);
            if (!denied.isEmpty() && trustedMods.contains(config.modId())) {
                trustedInUse.add(config.modId());
                continue;
            }
            if (!denied.isEmpty()) {
                blockers.add(config.modId());
                // Simple names, sorted. On a heavily-modded pack this line fires once per offending
                // config -- 14 times on the maintainer's own client -- and printing the raw Class set
                // made each one a ~400-character wall of fully-qualified names, identical every time.
                // The mod id and config name are the parts that differ and the parts worth reading.
                List<String> forced = new ArrayList<>();
                for (Class<?> family : denied) forced.add(family.getSimpleName());
                java.util.Collections.sort(forced);
                PathWeaver.LOG.warn("Mod '{}' config '{}' targets sensitive pathfinding code{}; "
                        + "forcing {} evaluator famil{} to sync pathing: {}",
                    config.modId(), config.configName(),
                    config.pluginContributed() ? " (plugin-expanded)" : "",
                    forced.size(), forced.size() == 1 ? "y" : "ies", String.join(", ", forced));
            }
        }
        blockingModIds = List.copyOf(blockers);
        trustedModIdsInUse = List.copyOf(trustedInUse);
        if (!trustedInUse.isEmpty()) {
            PathWeaver.LOG.warn("Running {} mod(s) unaudited at your request: {}. Their pathfinding "
                + "code executes on worker threads without having been checked. Matching is by mod "
                + "id, so this keeps applying after they update.", trustedInUse.size(),
                String.join(", ", trustedInUse));
        }
        for (String failure : decision.diagnostics()) {
            PathWeaver.LOG.warn("Foreign-mixin scan failure (fail-closed): {}", failure);
        }
        // A scan FAILURE is not waivable by the tier, only a scan DENIAL is.
        //
        // decide() converts any failure into a blanket denial of every family (see the
        // `!failures.isEmpty()` branch), so before this distinction existed, clearing the denial set
        // here also cleared "the scanner could not tell what is installed" -- an unreadable
        // fabric.mod.json, two mods claiming one config name, Mixin internals drifting, a declared
        // config that never prepared. Those are the thirteen fail-closed paths this class is built
        // around, and every one of them terminated in ALLOW on a stock install once UNSAFE became
        // the shipped default.
        //
        // "Run other mods' unaudited pathfinding code anyway" is a coherent thing to ask for and is
        // what the tier advertises. "Run even though the check malfunctioned" is a different request
        // that nobody made, and it fails in the worst possible way: the WARN block below would name
        // no mods at all, because blockingModIds() is built from configs that were successfully
        // read, so the operator is told nothing is responsible.
        if (!tierMayWaiveDenials(decision)) {
            PathWeaver.LOG.warn("=========================== PathWeaver ===========================");
            PathWeaver.LOG.warn("The compatibility scan FAILED ({} error(s), listed above), so it", decision.failed());
            PathWeaver.LOG.warn("cannot say what is installed. Every movement family stays");
            PathWeaver.LOG.warn("synchronous. compatibilityTier does NOT waive this: the tier waives");
            PathWeaver.LOG.warn("what the scan found, not the scan being unable to look.");
            PathWeaver.LOG.warn("==================================================================");
        } else if (ActiveCompatibilityPolicy.bypassesScan()
                && !SafetyGate.deniedBySafety.isEmpty()) {
            Set<Class<?>> overridden = Set.copyOf(SafetyGate.deniedBySafety);
            // Simple names. Set.toString() over Class objects prints
            // "[class net.minecraft.world.level.pathfinder.SwimNodeEvaluator, class ...]" -- one
            // 400-character line of fully-qualified noise in the block a user is most likely to read
            // and least likely to have context for. /pathweaver status already prints these by
            // simple name; the startup block should not be the worse of the two.
            List<String> deniedNames = new ArrayList<>();
            for (Class<?> family : overridden) deniedNames.add(family.getSimpleName());
            java.util.Collections.sort(deniedNames);
            SafetyGate.replaceDenials(Set.of());
            // Name the mods here, in this block, rather than saying "listed above". The per-config
            // lines that name them are emitted during early mixin scanning, hundreds of lines
            // earlier, in a format that reads as a warning about the other mod -- which is the same
            // argument that justified adding the world-start report. A block whose whole purpose is
            // to say "you are running these mods unaudited" should not make the reader go and find
            // out which ones.
            List<String> unauditedNowRunning = blockingModIds();
            PathWeaver.LOG.warn("=========================== PathWeaver ===========================");
            PathWeaver.LOG.warn("compatibilityTier=UNSAFE -- this is the shipped default. The scan");
            PathWeaver.LOG.warn("denied {}: {}", deniedNames.size(), String.join(", ", deniedNames));
            PathWeaver.LOG.warn("and that denial has been IGNORED. Path searches will now run on");
            PathWeaver.LOG.warn("worker threads alongside code that has not been audited for thread");
            PathWeaver.LOG.warn("safety. Use worlds you can afford to lose, and keep backups.");
            PathWeaver.LOG.warn("");
            // The one thing this block never said: WHY the unsafe tier is the default. Without it
            // the message reads as a mod shipping recklessly, when the actual trade is that the
            // checked tier denies everything on any ordinary modded pack -- measured at 0 of 187
            // eligible mob types -- so shipping it as the default would ship a mod that does
            // nothing. Deliberately not phrased as "this is safe on most packs": nothing measured
            // supports that, only an absence of reported corruption, which is not the same claim.
            PathWeaver.LOG.warn("Why this is the default: the checked tier (AUDITED) denies every");
            PathWeaver.LOG.warn("family on an ordinary modded pack -- measured at 0 of 187 eligible");
            PathWeaver.LOG.warn("mob types -- so shipping it would ship a mod that does nothing.");
            PathWeaver.LOG.warn("That is a trade, not a safety claim. Set compatibilityTier=AUDITED");
            PathWeaver.LOG.warn("for full checking, or name specific mods in trustedMods to keep the");
            PathWeaver.LOG.warn("scan armed for everything else.");
            PathWeaver.LOG.warn("");
            // What an operator should actually DO. The block above states a risk and leaves them
            // with no procedure, which is how a warning becomes noise people scroll past.
            PathWeaver.LOG.warn("Suggested: try it on a copy of your world. If mobs path normally");
            PathWeaver.LOG.warn("and nothing looks wrong after a few sessions, keep this setting. If");
            PathWeaver.LOG.warn("anything does look wrong, switch to AUDITED and report it -- that");
            PathWeaver.LOG.warn("is the evidence this project does not otherwise have.");
            if (!unauditedNowRunning.isEmpty()) {
                PathWeaver.LOG.warn("");
                PathWeaver.LOG.warn("The {} unaudited mod(s) now running on worker threads:",
                    unauditedNowRunning.size());
                PathWeaver.LOG.warn("  {}", String.join(", ", unauditedNowRunning));
            }
            PathWeaver.LOG.warn("");
            PathWeaver.LOG.warn("Set compatibilityTier=AUDITED for full checking, or list only some");
            PathWeaver.LOG.warn("of the above in trustedMods to accept those and keep checking the rest.");
            PathWeaver.LOG.warn("==================================================================");
        }
        PathWeaver.LOG.info("Foreign-mixin scan complete: scanned={}, failed={}, deniedFamilies={}.",
            decision.scanned(), decision.failed(), SafetyGate.deniedBySafety.size());
    }
}
