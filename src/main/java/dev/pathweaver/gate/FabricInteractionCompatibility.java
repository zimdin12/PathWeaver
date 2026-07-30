package dev.pathweaver.gate;

import com.google.gson.JsonObject;
import dev.pathweaver.config.CompatibilityTier;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModOrigin;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.pathfinder.NodeEvaluator;
import net.minecraft.world.level.pathfinder.PathFinder;
import net.minecraft.world.level.pathfinder.PathTypeCache;
import net.minecraft.world.level.pathfinder.PathfindingContext;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Bounded negative-reachability evidence for Fabric Events Interaction on MC 26.1.2.
 *
 * <p>Not a proof: the check inventories direct {@code BlockStateBase} calls from six pinned
 * worker-side classes. It does not traverse the whole worker call graph, and it does not build a
 * reverse callsite inventory for the two injected methods. Exact hashes keep that sample stable
 * against drift rather than making it exhaustive, which is why the exemption requires
 * {@code compatibilityTier=AUDITED}. See {@link #inspectRuntime}.
 */
final class FabricInteractionCompatibility {
    static final String MOD_ID = "fabric-events-interaction-v0";
    static final String MOD_VERSION = "5.2.2+07b380be4c";
    static final String CONFIG = "fabric-events-interaction-v0.mixins.json";
    static final String MIXIN =
        "net.fabricmc.fabric.mixin.event.interaction.BlockBehaviourBlockStateBaseMixin";
    static final String TARGET =
        "net.minecraft.world.level.block.state.BlockBehaviour$BlockStateBase";

    /**
     * The module jar legitimately ships in two byte-forms, so both are pinned.
     *
     * <p>Fabric API distributes this module twice: as a standalone Maven artifact, which is what a
     * Loom development environment resolves, and as a jar nested inside the aggregate
     * {@code fabric-api} jar, which is what every real install actually loads. The two are the same
     * module version but are not byte-identical -- the nested copy is recompressed. Pinning only the
     * development form made this audit fail on real servers while passing every game test, so the
     * gate reported a clean scan in the harness and denied everything in production. Some other
     * modules (content registries, for one) happen to be byte-identical across both packagings and
     * never exposed this.
     *
     * <p>Both hashes are for module version {@code 5.2.2+07b380be4c}. The mixin class and config
     * bytes -- which are what the audit actually reasons about -- are identical in both forms and
     * remain pinned exactly, so accepting either packaging does not widen what is trusted.
     */
    private static final Set<String> MODULE_SHAS = Set.of(
        // standalone Maven artifact, as resolved in a Loom dev environment
        "dc4a15c9250c6d0e5839e5b696792b06869c65f1ab7e71627986d8f9ed247d60",
        // nested inside the aggregate fabric-api jar, as shipped to users
        "c86603921ac5fd84135a7af31d54de3761d3ad027cba3694c29945cec8c3e2bf");
    private static final String CONFIG_SHA = "9a8445db121fce8e80c928290b8623f2f6e126459fddcb259b2016ae777f9759";
    private static final String MIXIN_SHA = "c35a9d60b12e32f2b1540b0116f6459bf515e8d1901dc18be5ebff9fd5bf72e7";
    private static final String BLOCK_STATE_BASE_SHA = "91a6b29e9ec0bd3ca18c05cd677b3a8e689c7849a3793c27373e531f9a1834fb";
    private static final String PATH_FINDER_SHA = "095d620eaac37aa71af017858682e89689039a3b999cf2a5fcfce3f1c3973b2c";
    private static final String NODE_EVALUATOR_SHA = "8ac7d5eef6bad45b148a051ee8d5d3d890281c66672d761e785361c506f421e1";
    private static final String WALK_SHA = "dd94893c06c47e3bb386cf3e521ffa2c8c71d31e547b162a138c02ac9312568f";
    private static final String PATH_CONTEXT_SHA = "30aae3ceae3c27e7f3071d8d9b8232035ad8b15ae8d7999da3fbbaa49add6a9b";
    private static final String PATH_TYPE_CACHE_SHA = "1073ec12b68b267a928316c2ddab85cd7af2220c8b8eeca482a84fdff164a7d3";
    private static final String PATH_REGION_SHA = "96b574637c3782d38ea2af0534ee644bd899fdf19f803bfd6bebe922ab016d8d";

    private static final String MIXIN_DESC = "Lorg/spongepowered/asm/mixin/Mixin;";
    private static final String SHADOW_DESC = "Lorg/spongepowered/asm/mixin/Shadow;";
    private static final String INJECT_DESC = "Lorg/spongepowered/asm/mixin/injection/Inject;";
    private static final String TARGET_INTERNAL = TARGET.replace('.', '/');
    private static final String BLOCK_STATE_INTERNAL = "net/minecraft/world/level/block/state/BlockState";
    private static final String USE_ITEM_ON = "useItemOn(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/InteractionHand;Lnet/minecraft/world/phys/BlockHitResult;)Lnet/minecraft/world/InteractionResult;";
    private static final String USE_WITHOUT_ITEM = "useWithoutItem(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/phys/BlockHitResult;)Lnet/minecraft/world/InteractionResult;";
    private static final Set<String> EXPECTED_TARGETS = Set.of(USE_ITEM_ON, USE_WITHOUT_ITEM);
    private static final Set<String> EXPECTED_WORKER_BLOCK_STATE_CALLS = Set.of(
        "is(Lnet/minecraft/tags/TagKey;)Z", "is(Ljava/lang/Object;)Z",
        "getFluidState()Lnet/minecraft/world/level/material/FluidState;", "isAir()Z",
        "isPathfindable(Lnet/minecraft/world/level/pathfinder/PathComputationType;)Z",
        "getCollisionShape(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/phys/shapes/VoxelShape;",
        "getBlock()Lnet/minecraft/world/level/block/Block;",
        "getValue(Lnet/minecraft/world/level/block/state/properties/Property;)Ljava/lang/Comparable;"
    );

    private FabricInteractionCompatibility() {}

    record Bundle(byte[] moduleJar, byte[] config, byte[] mixin, byte[] blockStateBase,
                  byte[] pathFinder, byte[] nodeEvaluator, byte[] walkNodeEvaluator,
                  byte[] pathContext, byte[] pathTypeCache, byte[] pathRegion) {}

    record Verification(boolean valid, List<String> diagnostics, Set<String> injectedTargets,
                        Set<String> workerBlockStateCalls) {
        Verification {
            diagnostics = List.copyOf(diagnostics);
            injectedTargets = Set.copyOf(injectedTargets);
            workerBlockStateCalls = Set.copyOf(workerBlockStateCalls);
        }
    }

    static Verification verifyBundle(Bundle b) {
        List<String> diagnostics = new ArrayList<>();
        String moduleSha = AuditedMixinCompatibility.sha256(b.moduleJar());
        if (!MODULE_SHAS.contains(moduleSha)) {
            diagnostics.add("module jar hash mismatch: " + moduleSha);
        }
        checkHash("mixin config", b.config(), CONFIG_SHA, diagnostics);
        checkHash("interaction mixin", b.mixin(), MIXIN_SHA, diagnostics);
        checkHash("vanilla BlockStateBase", b.blockStateBase(), BLOCK_STATE_BASE_SHA, diagnostics);
        checkHash("vanilla PathFinder", b.pathFinder(), PATH_FINDER_SHA, diagnostics);
        checkHash("vanilla NodeEvaluator", b.nodeEvaluator(), NODE_EVALUATOR_SHA, diagnostics);
        checkHash("vanilla WalkNodeEvaluator", b.walkNodeEvaluator(), WALK_SHA, diagnostics);
        checkHash("vanilla PathfindingContext", b.pathContext(), PATH_CONTEXT_SHA, diagnostics);
        checkHash("vanilla PathTypeCache", b.pathTypeCache(), PATH_TYPE_CACHE_SHA, diagnostics);
        checkHash("vanilla PathNavigationRegion", b.pathRegion(), PATH_REGION_SHA, diagnostics);
        Set<String> targets = new HashSet<>();
        Set<String> workerCalls = new HashSet<>();
        try {
            verifyConfig(b.config(), diagnostics);
            verifyMixin(b.mixin(), b.blockStateBase(), targets, diagnostics);
            for (byte[] bytes : List.of(b.pathFinder(), b.nodeEvaluator(), b.walkNodeEvaluator(),
                    b.pathContext(), b.pathTypeCache(), b.pathRegion())) {
                collectBlockStateCalls(bytes, workerCalls);
            }
            if (!workerCalls.equals(EXPECTED_WORKER_BLOCK_STATE_CALLS)) {
                diagnostics.add("worker BlockState invocation inventory drift: " + workerCalls);
            }
            if (workerCalls.contains(USE_ITEM_ON) || workerCalls.contains(USE_WITHOUT_ITEM)) {
                diagnostics.add("worker route reaches Fabric interaction target");
            }
        } catch (Throwable t) {
            diagnostics.add("interaction ASM/config proof failed: " + t);
        }
        return new Verification(diagnostics.isEmpty(), diagnostics, targets, workerCalls);
    }

    /**
     * Evidence for the Fabric interaction module, honoured above the strict tier only.
     *
     * <p>This was previously treated as a structural non-reachability proof and honoured at
     * {@link CompatibilityTier#STRICT}. It is not one. The check inventories direct
     * {@code BlockStateBase} invocations from six pinned worker-side classes, which is a bounded
     * sample: it does not traverse the full worker call graph through helpers, synthetic accessors
     * and virtual evaluator routes, and it does not establish a whole-game reverse inventory of
     * callsites for {@code useItemOn} and {@code useWithoutItem}. Exact hashes make that sample
     * stable against drift; they do not make it exhaustive.
     *
     * <p>So it is evidence, not proof, and belongs at {@link CompatibilityTier#AUDITED}. Completing
     * the reverse-callsite inventory would move it back.
     */
    static ForeignMixinScanner.AuditedExemptionEvidence inspectRuntime(
            FabricLoader loader, ModContainer module, CompatibilityTier tier) {
        try {
            if (!tier.allowsAudited()) {
                return ForeignMixinScanner.AuditedExemptionEvidence.unverified(
                    "Fabric interaction non-reachability is a bounded audit, not a structural proof; "
                        + "compatibilityTier=" + tier + " keeps it synchronous. "
                        + "Set compatibilityTier=AUDITED to allow it.");
            }
            if (!MOD_ID.equals(module.getMetadata().getId())
                    || !MOD_VERSION.equals(module.getMetadata().getVersion().getFriendlyString())) {
                return ForeignMixinScanner.AuditedExemptionEvidence.unverified(
                    "unsupported Fabric interaction module identity");
            }
            String minecraft = loader.getModContainer("minecraft")
                .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("missing");
            if (!FabricSwimCompatibility.MINECRAFT_VERSION.equals(minecraft)) {
                return ForeignMixinScanner.AuditedExemptionEvidence.unverified(
                    "unsupported Minecraft version for Fabric interaction audit: " + minecraft);
            }
            Bundle bundle = runtimeBundle(module);
            Verification verification = verifyBundle(bundle);
            if (!verification.valid()) {
                return ForeignMixinScanner.AuditedExemptionEvidence.unverified(
                    String.join("; ", verification.diagnostics()));
            }
            return exactEvidence();
        } catch (Throwable t) {
            return ForeignMixinScanner.AuditedExemptionEvidence.unverified(
                "exact Fabric interaction runtime verification failed: " + t);
        }
    }

    static Bundle runtimeBundle(ModContainer module) throws IOException {
        return new Bundle(readModuleArtifact(module), readModResource(module, CONFIG),
            readModResource(module, MIXIN.replace('.', '/') + ".class"),
            readClassBytes(BlockBehaviour.BlockStateBase.class), readClassBytes(PathFinder.class),
            readClassBytes(NodeEvaluator.class), readClassBytes(WalkNodeEvaluator.class),
            readClassBytes(PathfindingContext.class), readClassBytes(PathTypeCache.class),
            readClassBytes(PathNavigationRegion.class));
    }

    static ForeignMixinScanner.AuditedExemptionEvidence exactEvidence() {
        return ForeignMixinScanner.AuditedExemptionEvidence.verified(
            new ForeignMixinScanner.AuditKey(MOD_ID, MOD_VERSION, CONFIG, MIXIN, TARGET, null));
    }

    private static void verifyConfig(byte[] bytes, List<String> diagnostics) {
        JsonObject config = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
        if (!"net.fabricmc.fabric.mixin.event.interaction".equals(config.get("package").getAsString())) {
            diagnostics.add("unexpected interaction mixin package");
        }
        if (config.has("plugin")) diagnostics.add("interaction config unexpectedly has a plugin");
        int count = 0;
        for (var element : config.getAsJsonArray("mixins")) {
            if ("BlockBehaviourBlockStateBaseMixin".equals(element.getAsString())) count++;
        }
        if (count != 1) diagnostics.add("expected exactly one BlockStateBase interaction mixin");
    }

    private static void verifyMixin(byte[] bytes, byte[] vanilla, Set<String> targets,
                                    List<String> diagnostics) {
        ClassNode node = classNode(bytes);
        AnnotationNode mixin = findAnnotation(node.visibleAnnotations, node.invisibleAnnotations, MIXIN_DESC);
        Object values = annotationValue(mixin, "value");
        if (!(values instanceof List<?> list) || list.size() != 1
                || !(list.get(0) instanceof Type type) || !TARGET_INTERNAL.equals(type.getInternalName())) {
            diagnostics.add("interaction mixin target drift");
        }
        if (!node.fields.isEmpty() || !node.interfaces.isEmpty()) {
            diagnostics.add("interaction mixin field/interface shape drift");
        }
        ClassNode target = classNode(vanilla);
        int constructor = 0, shadow = 0, injectors = 0;
        for (MethodNode method : node.methods) {
            List<AnnotationNode> annotations = annotations(method);
            if (method.name.equals("<init>") && method.desc.equals("()V") && annotations.isEmpty()) {
                constructor++;
                continue;
            }
            if (method.name.equals("asState")
                    && method.desc.equals("()Lnet/minecraft/world/level/block/state/BlockState;")
                    && findAnnotation(method.visibleAnnotations, method.invisibleAnnotations, SHADOW_DESC) != null
                    && annotations.size() == 1) {
                shadow++;
                continue;
            }
            if (method.name.equals("<clinit>")) {
                diagnostics.add("interaction mixin unexpectedly declares clinit");
                continue;
            }
            if (annotations.size() != 1 || !INJECT_DESC.equals(annotations.get(0).desc)) {
                diagnostics.add("unexpected interaction mixin method/annotation: " + method.name + method.desc);
                continue;
            }
            AnnotationNode inject = annotations.get(0);
            Object selector = annotationValue(inject, "method");
            String expectedName = method.name.equals("callUseItemOnEvent") ? "useItemOn"
                : method.name.equals("callUseWithoutItemEvent") ? "useWithoutItem" : null;
            if (expectedName == null || !annotationContains(selector, expectedName)
                    || !Boolean.TRUE.equals(annotationValue(inject, "cancellable"))
                    || !headOnly(annotationValue(inject, "at"))) {
                diagnostics.add("interaction injector selector/HEAD/cancellable drift: " + method.name);
                continue;
            }
            List<MethodNode> matches = target.methods.stream()
                .filter(m -> m.name.equals(expectedName)).toList();
            if (matches.size() != 1) {
                diagnostics.add("vanilla interaction target descriptor ambiguity: " + expectedName);
                continue;
            }
            targets.add(expectedName + matches.get(0).desc);
            injectors++;
        }
        if (constructor != 1 || shadow != 1 || injectors != 2 || node.methods.size() != 4
                || !targets.equals(EXPECTED_TARGETS)) {
            diagnostics.add("interaction mixin exact method shape drift: ctor=" + constructor
                + " shadow=" + shadow + " injectors=" + injectors + " targets=" + targets);
        }
    }

    private static void collectBlockStateCalls(byte[] bytes, Set<String> calls) {
        for (MethodNode method : classNode(bytes).methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call && BLOCK_STATE_INTERNAL.equals(call.owner)) {
                    calls.add(call.name + call.desc);
                }
            }
        }
    }

    private static boolean headOnly(Object value) {
        if (!(value instanceof List<?> list) || list.size() != 1
                || !(list.get(0) instanceof AnnotationNode at)) return false;
        return "HEAD".equals(annotationValue(at, "value"));
    }

    private static ClassNode classNode(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static List<AnnotationNode> annotations(MethodNode method) {
        List<AnnotationNode> result = new ArrayList<>();
        if (method.visibleAnnotations != null) result.addAll(method.visibleAnnotations);
        if (method.invisibleAnnotations != null) result.addAll(method.invisibleAnnotations);
        return result;
    }

    private static AnnotationNode findAnnotation(List<AnnotationNode> visible,
                                                  List<AnnotationNode> invisible, String desc) {
        if (visible != null) for (AnnotationNode a : visible) if (desc.equals(a.desc)) return a;
        if (invisible != null) for (AnnotationNode a : invisible) if (desc.equals(a.desc)) return a;
        return null;
    }

    private static Object annotationValue(AnnotationNode annotation, String key) {
        if (annotation == null || annotation.values == null) return null;
        for (int i = 0; i < annotation.values.size(); i += 2) {
            if (key.equals(annotation.values.get(i))) return annotation.values.get(i + 1);
        }
        return null;
    }

    private static boolean annotationContains(Object value, String expected) {
        if (expected.equals(value)) return true;
        return value instanceof List<?> list && list.size() == 1 && expected.equals(list.get(0));
    }

    private static void checkHash(String label, byte[] bytes, String expected,
                                  List<String> diagnostics) {
        String actual = sha256(bytes);
        if (!expected.equals(actual)) diagnostics.add(label + " hash mismatch: " + actual);
    }

    private static String sha256(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] readModuleArtifact(ModContainer module) throws IOException {
        ModOrigin origin = module.getOrigin();
        if (origin.getKind() == ModOrigin.Kind.NESTED) {
            ModContainer parent = module.getContainingMod()
                .orElseThrow(() -> new IOException("nested module has no containing mod"));
            Path nested = parent.findPath(origin.getParentSubLocation())
                .orElseThrow(() -> new IOException("nested module artifact missing"));
            return Files.readAllBytes(nested);
        }
        if (origin.getKind() == ModOrigin.Kind.PATH) {
            List<Path> regular = origin.getPaths().stream().filter(Files::isRegularFile).toList();
            if (regular.size() != 1) throw new IOException("module PATH origin must have exactly one artifact");
            return Files.readAllBytes(regular.get(0));
        }
        throw new IOException("unsupported module origin: " + origin.getKind());
    }

    private static byte[] readModResource(ModContainer module, String resource) throws IOException {
        Path path = module.findPath(resource)
            .orElseThrow(() -> new IOException("module resource missing: " + resource));
        return Files.readAllBytes(path);
    }

    private static byte[] readClassBytes(Class<?> type) throws IOException {
        String name = type.getName().substring(type.getPackageName().length() + 1) + ".class";
        try (InputStream in = type.getResourceAsStream(name)) {
            if (in == null) throw new IOException("class resource missing: " + type.getName());
            return in.readAllBytes();
        }
    }
}
