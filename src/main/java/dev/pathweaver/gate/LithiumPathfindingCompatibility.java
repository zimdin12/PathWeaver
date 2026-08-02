package dev.pathweaver.gate;

import dev.pathweaver.config.CompatibilityTier;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Exact runtime fingerprints and an ASM proof for Lithium's pathfinding mixins.
 *
 * <p>Lithium is the single largest reason PathWeaver does nothing on real servers: it ships in most
 * performance modpacks and rewrites the code a worker reads. It is deliberately <em>not</em> a
 * a structural-proof exemption, because the other exempt mods are exempt on the
 * grounds that a worker provably cannot observe their change at all, and that is not true here.
 *
 * <p>What the audit does establish, from the bytecode rather than from documentation:
 * <ul>
 *   <li>Every field write in {@code BlockStateBaseMixin} lives in the constructor or in
 *       {@code lithium$initializePathNodeTypeCache()}. The getters a search calls are pure reads.
 *   <li>{@code WalkNodeEvaluatorMixin}, {@code FlyNodeEvaluatorMixin}, {@code PathfindingContextMixin}
 *       and {@code PathfindingContextAccessor} contain no field writes at all.
 *   <li>{@code PathNavigationRegionMixin} writes only from its constructor injection — which
 *       PathWeaver runs on the main thread at dispatch, before any worker sees the region — and
 *       from a static initializer, which the JVM runs exactly once.
 *   <li>Nothing on the search path calls the cache initializer, so a worker cannot trigger a
 *       lazy write even if initialization has not happened yet.
 * </ul>
 *
 * <p>So Lithium cannot corrupt shared state from a worker. It does still add live section and
 * palette reads, which means a search running concurrently with a block change can observe a
 * torn or stale view and return a worse path, and can be exposed to a concurrent-resize
 * exception. That failure is contained — it surfaces as a failed search, which falls back to
 * synchronous pathfinding — but it is a real behavioural risk rather than a proof of equivalence.
 * Trading a possible wrong path for tick headroom is a judgement call that belongs to the server
 * owner, which is why this exemption only decides anything at {@link CompatibilityTier#AUDITED}.
 * That is the opt-in checked tier, not the shipped default -- at the default the scan is waived
 * wholesale and this audit decides nothing at all.
 *
 * <p>The exemption is pinned to exact artifact bytes. A different Lithium build, a changed mixin
 * class, or a changed mixin plugin fails verification and denies, because the proof above is a
 * statement about <em>these</em> bytes and nothing else.
 */
final class LithiumPathfindingCompatibility {
    static final String MOD_ID = "lithium";
    static final String MOD_VERSION = "0.24.6+mc26.1.2";
    static final String CONFIG = "lithium.mixins.json";
    static final String FABRIC_CONFIG = "lithium-fabric.mixins.json";
    static final String PLUGIN = "net.caffeinemc.mods.lithium.mixin.LithiumMixinPlugin";
    private static final String PACKAGE = "net.caffeinemc.mods.lithium.mixin";
    private static final String PATHING = PACKAGE + ".ai.pathing.";

    static final String BLOCK_STATE_MIXIN = PATHING + "BlockStateBaseMixin";
    static final String WALK_MIXIN = PATHING + "WalkNodeEvaluatorMixin";
    static final String REGION_MIXIN = PATHING + "PathNavigationRegionMixin";
    static final String CONTEXT_MIXIN = PATHING + "PathfindingContextMixin";
    static final String CONTEXT_ACCESSOR = PATHING + "PathfindingContextAccessor";
    static final String FLAGS_MIXIN = PACKAGE + ".util.block_tracking.BlockStateBaseMixin";
    static final String CHUNK_REGION_MIXIN =
        PACKAGE + ".util.chunk_access.PathNavigationRegionMixin";
    static final String NAVIGATION_MIXIN =
        PACKAGE + ".entity.inactive_navigations.PathNavigationMixin";

    static final String BLOCK_STATE_BASE =
        "net.minecraft.world.level.block.state.BlockBehaviour$BlockStateBase";
    static final String PATH_REGION = "net.minecraft.world.level.PathNavigationRegion";
    static final String PATH_CONTEXT = "net.minecraft.world.level.pathfinder.PathfindingContext";
    static final String WALK = "net.minecraft.world.level.pathfinder.WalkNodeEvaluator";
    static final String PATH_NAVIGATION =
        "net.minecraft.world.entity.ai.navigation.PathNavigation";

    private static final String MINECRAFT_VERSION = "26.1.2";
    private static final String MODULE_SHA =
        "509e7f770c7d48bd37e9592917329db2768e4695c72a43e22c19ef64d0f9839f";
    private static final String CONFIG_SHA =
        "f9674d7b9bb56ba70aedae56bb07c46ed82b94f554c8573a1a8420350827dd37";
    private static final String FABRIC_CONFIG_SHA =
        "e1bfe4635f34f0924b85d607fbd2416896a6591176bd4849b19047dd27c40c29";
    static final String PLUGIN_SHA =
        "b97aed37b9ed2f2bd81868682ce8aac62808ec775fa3899afbca751ea204226a";
    private static final String BLOCK_STATE_MIXIN_SHA =
        "98e0029073adbf8ff610e6e69af696fc28d914b17e8c0d0bd78a22a806fccd19";
    private static final String WALK_MIXIN_SHA =
        "ac04c4283d7502861410749c5d77ab83e02639166504f948630dee15a6953c73";
    private static final String REGION_MIXIN_SHA =
        "cb06d8689a5a77e54e77d0443611d3c3b3929b3ef61d0b423a7caead44d93593";
    private static final String CONTEXT_MIXIN_SHA =
        "2dbd5a9f785ee775b8070b3add8a878154eb2d514e3df0b8a7878e449c2e2fea";
    private static final String CONTEXT_ACCESSOR_SHA =
        "88bac968c7a2476d802617aab427a54138cfffa49160b09981ccc3c01e3105b1";
    private static final String FLAGS_MIXIN_SHA =
        "4471cdb6ee762517ee42b1f7f4e2fc78e477547940900f1c34c290d1c811fc75";
    private static final String CHUNK_REGION_MIXIN_SHA =
        "4bd80e9ef6c9bdccd0fdb1544cf5f7efc18fab24a5dca546e36eb2a6f94d885d";
    private static final String NAVIGATION_MIXIN_SHA =
        "0c14996f3832bd7e8f2c51a963fa260f7a4f95bb3fe6311098a33102340ef146";

    private static final String INJECT_DESC = "Lorg/spongepowered/asm/mixin/injection/Inject;";
    /** The one method allowed to write the block-state path-type cache. */
    private static final String CACHE_INITIALIZER = "lithium$initializePathNodeTypeCache";
    /** The one method allowed to write the block-state flag cache. */
    private static final String FLAGS_INITIALIZER = "lithium$initializeFlags";
    /** Lithium's constructor injection into {@code PathNavigationRegion}. */
    private static final String REGION_CTOR_INJECTION = "init";

    private LithiumPathfindingCompatibility() {}

    record Bundle(byte[] moduleJar, byte[] config, byte[] fabricConfig, byte[] plugin,
                  byte[] blockStateMixin, byte[] walkMixin, byte[] regionMixin,
                  byte[] contextMixin, byte[] contextAccessor, byte[] flagsMixin,
                  byte[] chunkRegionMixin, byte[] navigationMixin,
                  byte[] vanillaPathFinder) {}

    /**
     * Verify the loaded Lithium artifact, but only once the operator has opted into the audited
     * tier. Below that tier this returns nothing, so the ordinary denial stands and Lithium keeps
     * pathfinding synchronous — the tier is enforced by withholding evidence rather than by
     * suppressing a denial, so there is no path where a claim is exempted without a live proof.
     */
    static ForeignMixinScanner.AuditedExemptionEvidence inspectRuntime(
            FabricLoader loader, ModContainer module, CompatibilityTier tier) {
        try {
            String id = module.getMetadata().getId();
            if (!MOD_ID.equals(id)) {
                return ForeignMixinScanner.AuditedExemptionEvidence.unverified(
                    id + " is not the Lithium compatibility owner");
            }
            if (!tier.allowsAudited()) {
                return ForeignMixinScanner.AuditedExemptionEvidence.unverified(
                    "Lithium modifies pathfinding; compatibilityTier=" + tier
                        + " keeps it synchronous. Set compatibilityTier=AUDITED to allow it.");
            }
            String version = module.getMetadata().getVersion().getFriendlyString();
            String minecraft = loader.getModContainer("minecraft")
                .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("missing");
            if (!MINECRAFT_VERSION.equals(minecraft)) {
                return unverified("exact audit unsupported on Minecraft " + minecraft);
            }
            if (!MOD_VERSION.equals(version)) return unverified("unsupported version " + version);

            List<String> diagnostics = verify(runtimeBundle(module));
            return diagnostics.isEmpty() ? exactEvidence()
                : new ForeignMixinScanner.AuditedExemptionEvidence(Set.of(), diagnostics);
        } catch (Throwable t) {
            return ForeignMixinScanner.AuditedExemptionEvidence.unverified(
                "Lithium exact audit failed: " + t);
        }
    }

    static Bundle runtimeBundle(ModContainer module) throws java.io.IOException {
        return new Bundle(
            AuditedMixinCompatibility.readModuleArtifact(module),
            AuditedMixinCompatibility.readModResource(module, CONFIG),
            AuditedMixinCompatibility.readModResource(module, FABRIC_CONFIG),
            AuditedMixinCompatibility.readModResource(module,
                AuditedMixinCompatibility.classResource(PLUGIN)),
            AuditedMixinCompatibility.readModResource(module,
                AuditedMixinCompatibility.classResource(BLOCK_STATE_MIXIN)),
            AuditedMixinCompatibility.readModResource(module,
                AuditedMixinCompatibility.classResource(WALK_MIXIN)),
            AuditedMixinCompatibility.readModResource(module,
                AuditedMixinCompatibility.classResource(REGION_MIXIN)),
            AuditedMixinCompatibility.readModResource(module,
                AuditedMixinCompatibility.classResource(CONTEXT_MIXIN)),
            AuditedMixinCompatibility.readModResource(module,
                AuditedMixinCompatibility.classResource(CONTEXT_ACCESSOR)),
            AuditedMixinCompatibility.readModResource(module,
                AuditedMixinCompatibility.classResource(FLAGS_MIXIN)),
            AuditedMixinCompatibility.readModResource(module,
                AuditedMixinCompatibility.classResource(CHUNK_REGION_MIXIN)),
            AuditedMixinCompatibility.readModResource(module,
                AuditedMixinCompatibility.classResource(NAVIGATION_MIXIN)),
            AuditedMixinCompatibility.readClassBytes(
                net.minecraft.world.level.pathfinder.PathFinder.class));
    }

    /** The five claims Lithium contributes that would otherwise deny an eligible evaluator. */
    static ForeignMixinScanner.AuditedExemptionEvidence exactEvidence() {
        ForeignMixinScanner.PluginIdentity plugin =
            new ForeignMixinScanner.PluginIdentity(PLUGIN, PLUGIN_SHA);
        java.util.Set<ForeignMixinScanner.AuditKey> keys = new java.util.HashSet<>();
        for (String[] pair : new String[][] {
                {BLOCK_STATE_MIXIN, BLOCK_STATE_BASE},
                {WALK_MIXIN, WALK},
                {REGION_MIXIN, PATH_REGION},
                {CONTEXT_MIXIN, PATH_CONTEXT},
                {CONTEXT_ACCESSOR, PATH_CONTEXT},
                {FLAGS_MIXIN, BLOCK_STATE_BASE},
                {CHUNK_REGION_MIXIN, PATH_REGION},
                {NAVIGATION_MIXIN, PATH_NAVIGATION}}) {
            keys.add(new ForeignMixinScanner.AuditKey(MOD_ID, MOD_VERSION, CONFIG,
                pair[0], pair[1], plugin));
        }
        return new ForeignMixinScanner.AuditedExemptionEvidence(keys, List.of());
    }

    static List<String> verify(Bundle bundle) {
        List<String> diagnostics = new ArrayList<>();
        AuditedMixinCompatibility.checkHash("Lithium module jar", bundle.moduleJar(),
            MODULE_SHA, diagnostics);
        AuditedMixinCompatibility.checkHash("Lithium mixin config", bundle.config(),
            CONFIG_SHA, diagnostics);
        AuditedMixinCompatibility.checkHash("Lithium Fabric mixin config", bundle.fabricConfig(),
            FABRIC_CONFIG_SHA, diagnostics);
        AuditedMixinCompatibility.checkHash("Lithium mixin plugin", bundle.plugin(),
            PLUGIN_SHA, diagnostics);
        AuditedMixinCompatibility.checkHash("Lithium BlockStateBaseMixin", bundle.blockStateMixin(),
            BLOCK_STATE_MIXIN_SHA, diagnostics);
        AuditedMixinCompatibility.checkHash("Lithium WalkNodeEvaluatorMixin", bundle.walkMixin(),
            WALK_MIXIN_SHA, diagnostics);
        AuditedMixinCompatibility.checkHash("Lithium PathNavigationRegionMixin", bundle.regionMixin(),
            REGION_MIXIN_SHA, diagnostics);
        AuditedMixinCompatibility.checkHash("Lithium PathfindingContextMixin", bundle.contextMixin(),
            CONTEXT_MIXIN_SHA, diagnostics);
        AuditedMixinCompatibility.checkHash("Lithium PathfindingContextAccessor",
            bundle.contextAccessor(), CONTEXT_ACCESSOR_SHA, diagnostics);
        AuditedMixinCompatibility.checkHash("Lithium block-tracking BlockStateBaseMixin",
            bundle.flagsMixin(), FLAGS_MIXIN_SHA, diagnostics);
        AuditedMixinCompatibility.checkHash("Lithium chunk-access PathNavigationRegionMixin",
            bundle.chunkRegionMixin(), CHUNK_REGION_MIXIN_SHA, diagnostics);
        AuditedMixinCompatibility.checkHash("Lithium inactive-navigations PathNavigationMixin",
            bundle.navigationMixin(), NAVIGATION_MIXIN_SHA, diagnostics);
        try {
            for (String mixin : List.of("ai.pathing.BlockStateBaseMixin",
                    "ai.pathing.WalkNodeEvaluatorMixin", "ai.pathing.PathNavigationRegionMixin",
                    "ai.pathing.PathfindingContextMixin",
                    "ai.pathing.PathfindingContextAccessor",
                    "util.block_tracking.BlockStateBaseMixin",
                    "util.chunk_access.PathNavigationRegionMixin",
                    "entity.inactive_navigations.PathNavigationMixin")) {
                AuditedMixinCompatibility.verifyConfig(bundle.config(), PACKAGE, PLUGIN,
                    mixin, diagnostics);
            }
            requireTarget(bundle.blockStateMixin(),
                "net/minecraft/world/level/block/state/BlockBehaviour$BlockStateBase", diagnostics);
            requireTarget(bundle.walkMixin(),
                "net/minecraft/world/level/pathfinder/WalkNodeEvaluator", diagnostics);
            requireTarget(bundle.regionMixin(),
                "net/minecraft/world/level/PathNavigationRegion", diagnostics);
            requireTarget(bundle.contextMixin(),
                "net/minecraft/world/level/pathfinder/PathfindingContext", diagnostics);
            requireTarget(bundle.contextAccessor(),
                "net/minecraft/world/level/pathfinder/PathfindingContext", diagnostics);
            requireTarget(bundle.flagsMixin(),
                "net/minecraft/world/level/block/state/BlockBehaviour$BlockStateBase",
                diagnostics);
            requireTarget(bundle.chunkRegionMixin(),
                "net/minecraft/world/level/PathNavigationRegion", diagnostics);
            requireTarget(bundle.navigationMixin(),
                "net/minecraft/world/entity/ai/navigation/PathNavigation", diagnostics);

            // The load-bearing proof: nothing a worker executes writes shared state.
            requireWritesConfinedTo("Lithium BlockStateBaseMixin", bundle.blockStateMixin(),
                Set.of("<init>", "<clinit>", CACHE_INITIALIZER), diagnostics);
            requireWritesConfinedTo("Lithium PathNavigationRegionMixin", bundle.regionMixin(),
                Set.of("<init>", "<clinit>", REGION_CTOR_INJECTION), diagnostics);
            requireWritesConfinedTo("Lithium WalkNodeEvaluatorMixin", bundle.walkMixin(),
                Set.of("<init>", "<clinit>"), diagnostics);
            requireWritesConfinedTo("Lithium PathfindingContextMixin", bundle.contextMixin(),
                Set.of("<init>", "<clinit>"), diagnostics);
            requireWritesConfinedTo("Lithium PathfindingContextAccessor", bundle.contextAccessor(),
                Set.of("<init>", "<clinit>"), diagnostics);

            // A lazy initializer call from the search path would reintroduce the worker write the
            // proof above rules out, so the initializer must be unreachable from every other method.
            requireWritesConfinedTo("Lithium block-tracking BlockStateBaseMixin",
                bundle.flagsMixin(), Set.of("<init>", "<clinit>", FLAGS_INITIALIZER),
                diagnostics);
            requireWritesConfinedTo("Lithium chunk-access PathNavigationRegionMixin",
                bundle.chunkRegionMixin(), Set.of("<init>", "<clinit>"), diagnostics);

            requireInitializerConfined(bundle.blockStateMixin(), diagnostics);
            requireRegionInjectionIsConstructorOnly(bundle.regionMixin(), diagnostics);

            // The inactive-navigations mixin is the one Lithium hook that genuinely mutates
            // shared state: its handlers add and remove navigations from a listener set on
            // the level. That is safe here for a different reason than the others, because a
            // worker never runs it. The worker entry point is PathFinder.findPath, which has
            // no call edge into PathNavigation, so those handlers only execute on the main
            // thread. This is the same proof the rabbit-pathfinding-fix exemption rests on,
            // re-checked against the vanilla bytes actually loaded rather than assumed.
            AuditedMixinCompatibility.verifyRabbitTargetsNotReachableFromPathFinder(
                bundle.vanillaPathFinder(), diagnostics);
        } catch (Throwable t) {
            diagnostics.add("Lithium ASM/config shape parse failed: " + t);
        }
        return diagnostics;
    }

    private static void requireTarget(byte[] bytes, String internal, List<String> diagnostics) {
        AuditedMixinCompatibility.requireMixinTarget(
            AuditedMixinCompatibility.classNode(bytes), internal, diagnostics);
    }

    /** Fail unless every {@code PUTFIELD}/{@code PUTSTATIC} sits in one of the allowed methods. */
    static void requireWritesConfinedTo(String label, byte[] bytes,
                                                Set<String> allowedWriters,
                                                List<String> diagnostics) {
        ClassNode node = AuditedMixinCompatibility.classNode(bytes);
        for (MethodNode method : node.methods) {
            if (allowedWriters.contains(method.name)) continue;
            for (AbstractInsnNode insn : method.instructions) {
                int opcode = insn.getOpcode();
                if ((opcode == Opcodes.PUTFIELD || opcode == Opcodes.PUTSTATIC)
                        && insn instanceof FieldInsnNode field) {
                    diagnostics.add(label + " writes shared state outside initialization: "
                        + method.name + method.desc + " -> " + field.owner + "." + field.name);
                }
            }
        }
    }

    /**
     * Fail if any method other than the initializer itself calls it.
     *
     * <p>Scoped to the mixin class rather than the whole jar, because scanning every Lithium class
     * at startup is not worth the cost. That narrower check is sound only because the artifact is
     * hash-pinned, and the wider property was verified by hand against those exact bytes: across
     * the whole jar, {@code lithium$initializePathNodeTypeCache} is referenced only by its
     * interface, by {@code BlockInfoInitializer}, and by this mixin, and
     * {@code lithium$initializeFlags} only by {@code BlockStateFlagHolder},
     * {@code BlockInfoInitializer}, and its own mixin. No search-path caller exists. A different
     * Lithium build fails the hash check before reaching here, so it never inherits that finding.
     */
    static void requireInitializerConfined(byte[] bytes, List<String> diagnostics) {
        ClassNode node = AuditedMixinCompatibility.classNode(bytes);
        for (MethodNode method : node.methods) {
            if (CACHE_INITIALIZER.equals(method.name)) continue;
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call && CACHE_INITIALIZER.equals(call.name)) {
                    diagnostics.add("Lithium cache initializer is reachable from "
                        + method.name + method.desc + ", so a worker could trigger a write");
                }
            }
        }
    }

    /**
     * The region mixin's only writer must be an injection into the constructor. PathWeaver builds
     * the region on the main thread at dispatch, so a constructor-time write happens-before any
     * worker observes the object; a write from any other injection point would not.
     */
    static void requireRegionInjectionIsConstructorOnly(byte[] bytes,
                                                        List<String> diagnostics) {
        ClassNode node = AuditedMixinCompatibility.classNode(bytes);
        int checked = 0;
        // Every method carrying the allowed writer name must be verified, not just the first one
        // found. The write-confinement check above allows writes by method NAME, so a second
        // overload sharing that name would inherit permission to write while never being checked
        // for constructor-injection -- which is the entire basis for those writes being safe.
        for (MethodNode method : node.methods) {
            if (!REGION_CTOR_INJECTION.equals(method.name)) continue;
            checked++;
            AnnotationNode inject = findInject(method);
            if (inject == null || !mentionsConstructor(annotationValue(inject, "method"))) {
                diagnostics.add("Lithium region writer is not a constructor injection: "
                    + method.name + method.desc);
            }
        }
        if (checked == 0) diagnostics.add("Lithium region constructor injection is missing");
    }

    private static boolean mentionsConstructor(Object selector) {
        if (selector instanceof String value) return value.contains("<init>");
        return selector instanceof List<?> list
            && list.stream().anyMatch(v -> v instanceof String s && s.contains("<init>"));
    }

    private static AnnotationNode findInject(MethodNode method) {
        for (List<AnnotationNode> group :
                List.of(method.visibleAnnotations == null ? List.<AnnotationNode>of()
                            : method.visibleAnnotations,
                        method.invisibleAnnotations == null ? List.<AnnotationNode>of()
                            : method.invisibleAnnotations)) {
            for (AnnotationNode node : group) if (INJECT_DESC.equals(node.desc)) return node;
        }
        return null;
    }

    private static Object annotationValue(AnnotationNode annotation, String key) {
        if (annotation == null || annotation.values == null) return null;
        for (int i = 0; i < annotation.values.size(); i += 2) {
            if (key.equals(annotation.values.get(i))) return annotation.values.get(i + 1);
        }
        return null;
    }

    private static ForeignMixinScanner.AuditedExemptionEvidence unverified(String reason) {
        return ForeignMixinScanner.AuditedExemptionEvidence.unverified(
            "Lithium exact audit: " + reason);
    }
}
