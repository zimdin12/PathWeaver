package dev.pathweaver.gate;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModOrigin;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.pathfinder.NodeEvaluator;
import net.minecraft.world.level.pathfinder.PathFinder;
import net.minecraft.world.level.pathfinder.PathfindingContext;
import net.minecraft.world.level.pathfinder.SwimNodeEvaluator;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Runtime fingerprint and ASM proof for one exact Fabric Swim compatibility tuple. */
final class FabricSwimCompatibility {
    static final String MOD_ID = "fabric-content-registries-v0";
    static final String MOD_VERSION = "11.2.1+76b0b6bb4c";
    static final String CONFIG = "fabric-content-registries-v0.mixins.json";
    static final String CONTEXT_MIXIN =
        "net.fabricmc.fabric.mixin.content.registry.PathfindingContextMixin";
    static final String WALK_MIXIN =
        "net.fabricmc.fabric.mixin.content.registry.WalkNodeEvaluatorMixin";
    static final String BLOCK_STATE_BASE_MIXIN =
        "net.fabricmc.fabric.mixin.content.registry.BlockBehaviourBlockStateBaseMixin";

    private static final String MODULE_SHA =
        "d1c8a0a2753850ec422f9c03824a0475a24f1d27bbbf1227d9f9d952406bebd1";
    private static final String CONFIG_SHA =
        "0e9df73ad0f08696f4bf99024307b8b72151d13c7626f23e456d115b9eb65f9e";
    private static final String CONTEXT_MIXIN_SHA =
        "d0c6db69f100e9b49c81fc9ea205566ab0effeba6bc17a73e5884e8c1e0a951e";
    private static final String WALK_MIXIN_SHA =
        "9a762716beeeb06843108341ac6e0d1baadf7e1e1ba9b515b4ee9c27cf73fa7e";
    private static final String BLOCK_STATE_BASE_MIXIN_SHA =
        "79d862b4174e175e43c01c2ffd209db2248c193f696bbcd81475063df7f31fd7";
    private static final String LAND_REGISTRY_SHA =
        "292f7f5c80e2a7afe220e050940e83448e38262d1d517a3b89eb50f5ad138a9c";
    private static final String SWIM_SHA =
        "2c74707049f26c23a713ebcaf26569bc38925d6dd1d399aa078d0dbf53d6f889";
    private static final String NODE_EVALUATOR_SHA =
        "8ac7d5eef6bad45b148a051ee8d5d3d890281c66672d761e785361c506f421e1";
    private static final String PATH_FINDER_SHA =
        "095d620eaac37aa71af017858682e89689039a3b999cf2a5fcfce3f1c3973b2c";
    private static final String PATH_CONTEXT_SHA =
        "30aae3ceae3c27e7f3071d8d9b8232035ad8b15ae8d7999da3fbbaa49add6a9b";
    private static final String BLOCK_STATE_BASE_SHA =
        "91a6b29e9ec0bd3ca18c05cd677b3a8e689c7849a3793c27373e531f9a1834fb";

    private static final String CONTEXT_INTERNAL =
        "net/minecraft/world/level/pathfinder/PathfindingContext";
    private static final String INJECT_DESC =
        "Lorg/spongepowered/asm/mixin/injection/Inject;";
    private static final String MIXIN_DESC = "Lorg/spongepowered/asm/mixin/Mixin;";
    private static final String SHADOW_DESC = "Lorg/spongepowered/asm/mixin/Shadow;";
    private static final String OVERWRITE_DESC = "Lorg/spongepowered/asm/mixin/Overwrite;";
    private static final String BLOCK_STATE_BASE_INTERNAL =
        "net/minecraft/world/level/block/state/BlockBehaviour$BlockStateBase";
    private static final String RANDOM_TICK_REFRESHER_INTERNAL =
        "net/fabricmc/fabric/impl/content/registry/OxidizableBlocksRegistryImpl$RandomTickCacheRefresher";
    private static final String REGISTRY_INTERNAL =
        "net/fabricmc/fabric/api/registry/LandPathTypeRegistry";
    private static final Set<String> ALLOWED_SWIM_CONTEXT_CALLS = Set.of(
        "getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;",
        "level()Lnet/minecraft/world/level/CollisionGetter;"
    );

    private FabricSwimCompatibility() {}

    record Bundle(byte[] moduleJar, byte[] config, byte[] contextMixin, byte[] walkMixin,
                  byte[] blockStateBaseMixin, byte[] landRegistry, byte[] swim,
                  byte[] nodeEvaluator, byte[] pathFinder,
                  byte[] pathContext, byte[] blockStateBase) {}

    record Verification(boolean valid, List<String> diagnostics,
                        Set<String> fabricInjectedMethods,
                        Set<String> swimContextCalls,
                        boolean landRegistryVerified) {
        Verification {
            diagnostics = List.copyOf(diagnostics);
            fabricInjectedMethods = Set.copyOf(fabricInjectedMethods);
            swimContextCalls = Set.copyOf(swimContextCalls);
        }
    }

    static ForeignMixinScanner.AuditedExemptionEvidence exactLandEvidence() {
        return new ForeignMixinScanner.AuditedExemptionEvidence(Set.of(
            new ForeignMixinScanner.AuditKey(MOD_ID, MOD_VERSION, CONFIG, CONTEXT_MIXIN,
                "net.minecraft.world.level.pathfinder.PathfindingContext", null),
            new ForeignMixinScanner.AuditKey(MOD_ID, MOD_VERSION, CONFIG, WALK_MIXIN,
                "net.minecraft.world.level.pathfinder.WalkNodeEvaluator", null),
            new ForeignMixinScanner.AuditKey(MOD_ID, MOD_VERSION, CONFIG, BLOCK_STATE_BASE_MIXIN,
                "net.minecraft.world.level.block.state.BlockBehaviour$BlockStateBase", null)),
            List.of());
    }

    static ForeignMixinScanner.SwimExemptionEvidence inspectRuntime(
            FabricLoader loader, ModContainer module) {
        List<String> diagnostics = new ArrayList<>();
        try {
            if (!MOD_ID.equals(module.getMetadata().getId())) {
                return invalidEvidence("wrong module id: " + module.getMetadata().getId());
            }
            if (!MOD_VERSION.equals(module.getMetadata().getVersion().getFriendlyString())) {
                return invalidEvidence("unsupported module version: "
                    + module.getMetadata().getVersion().getFriendlyString());
            }
            String minecraft = loader.getModContainer("minecraft")
                .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("missing");
            // NOT gated on the Minecraft version string any more.
            //
            // Every audit here pins, by SHA-256, the vanilla classes its own proof reads -- and the
            // verification below fails on any of those hashes. So the bytes the proof was derived
            // against are already established by evidence, and the version label was a second,
            // coarser answer to the same question that could only ever be stricter than the bytes.
            //
            // Measured both directions before removing it. On 26.1.1 all ten pinned vanilla classes
            // are byte-identical to 26.1.2, so the audits hold and the label was the only thing
            // refusing. On 26.2 four of the ten differ -- PathNavigation, WalkNodeEvaluator,
            // BlockStateBase and Frog$FrogNodeEvaluator -- so the hashes refuse it without needing
            // the label at all. The running version is still reported in failure text, because
            // "which Minecraft" is useful in a diagnostic even when it is not the deciding fact.
            Bundle bundle = loadRuntimeBundle(module);
            Verification verification = verifyBundle(bundle);
            diagnostics.addAll(verification.diagnostics());
            if (!verification.valid()) {
                return new ForeignMixinScanner.SwimExemptionEvidence(false, diagnostics);
            }
            return new ForeignMixinScanner.SwimExemptionEvidence(true, diagnostics);
        } catch (Throwable t) {
            diagnostics.add("exact Fabric Swim fingerprint failed: " + t);
            return new ForeignMixinScanner.SwimExemptionEvidence(false, diagnostics);
        }
    }

    static ForeignMixinScanner.AuditedExemptionEvidence inspectLandRuntime(
            FabricLoader loader, ModContainer module) {
        ForeignMixinScanner.SwimExemptionEvidence identityAndBundle = inspectRuntime(loader, module);
        if (!identityAndBundle.verified()) {
            return ForeignMixinScanner.AuditedExemptionEvidence.unverified(
                String.join("; ", identityAndBundle.diagnostics()));
        }
        try {
            Verification verification = verifyBundle(loadRuntimeBundle(module));
            if (!verification.valid() || !verification.landRegistryVerified()) {
                return ForeignMixinScanner.AuditedExemptionEvidence.unverified(
                    String.join("; ", verification.diagnostics()));
            }
            return exactLandEvidence();
        } catch (Throwable t) {
            return ForeignMixinScanner.AuditedExemptionEvidence.unverified(
                "exact Fabric land-registry runtime verification failed: " + t);
        }
    }

    private static Bundle loadRuntimeBundle(ModContainer module) throws IOException {
        return new Bundle(
            readModuleArtifact(module),
            readModResource(module, CONFIG),
            readModResource(module, CONTEXT_MIXIN.replace('.', '/') + ".class"),
            readModResource(module, WALK_MIXIN.replace('.', '/') + ".class"),
            readModResource(module, BLOCK_STATE_BASE_MIXIN.replace('.', '/') + ".class"),
            readModResource(module, REGISTRY_INTERNAL + ".class"),
            readClassBytes(SwimNodeEvaluator.class), readClassBytes(NodeEvaluator.class),
            readClassBytes(PathFinder.class), readClassBytes(PathfindingContext.class),
            readClassBytes(BlockBehaviour.BlockStateBase.class));
    }

    static Verification verifyBundle(Bundle bundle) {
        List<String> diagnostics = new ArrayList<>();
        Set<String> injected = new HashSet<>();
        Set<String> swimCalls = new HashSet<>();
        checkHash("module jar", bundle.moduleJar(), MODULE_SHA, diagnostics);
        checkHash("mixin config", bundle.config(), CONFIG_SHA, diagnostics);
        checkHash("Fabric PathfindingContextMixin", bundle.contextMixin(), CONTEXT_MIXIN_SHA, diagnostics);
        checkHash("Fabric WalkNodeEvaluatorMixin", bundle.walkMixin(), WALK_MIXIN_SHA, diagnostics);
        checkHash("Fabric BlockBehaviourBlockStateBaseMixin", bundle.blockStateBaseMixin(),
            BLOCK_STATE_BASE_MIXIN_SHA, diagnostics);
        checkHash("Fabric LandPathTypeRegistry", bundle.landRegistry(), LAND_REGISTRY_SHA, diagnostics);
        checkHash("vanilla SwimNodeEvaluator", bundle.swim(), SWIM_SHA, diagnostics);
        checkHash("vanilla NodeEvaluator", bundle.nodeEvaluator(), NODE_EVALUATOR_SHA, diagnostics);
        checkHash("vanilla PathFinder", bundle.pathFinder(), PATH_FINDER_SHA, diagnostics);
        checkHash("vanilla PathfindingContext", bundle.pathContext(), PATH_CONTEXT_SHA, diagnostics);
        checkHash("vanilla BlockBehaviour.BlockStateBase", bundle.blockStateBase(),
            BLOCK_STATE_BASE_SHA, diagnostics);
        boolean landRegistryVerified = false;
        try {
            verifyConfig(bundle.config(), diagnostics);
            verifyFabricContextMixin(bundle.contextMixin(), injected, diagnostics);
            verifyStructuralBlockStateMixin(bundle.blockStateBaseMixin(), diagnostics);
            int beforeRegistry = diagnostics.size();
            verifyLandRegistry(bundle.landRegistry(), diagnostics);
            landRegistryVerified = diagnostics.size() == beforeRegistry;
            verifySwimRoute(bundle.swim(), bundle.nodeEvaluator(), bundle.pathFinder(),
                bundle.pathContext(), swimCalls, diagnostics);
        } catch (Throwable t) {
            diagnostics.add("ASM/config shape parse failed: " + t);
        }
        return new Verification(diagnostics.isEmpty(), diagnostics, injected, swimCalls,
            landRegistryVerified);
    }

    private static void verifyConfig(byte[] bytes, List<String> diagnostics) {
        JsonObject config = JsonParser.parseString(new String(bytes, java.nio.charset.StandardCharsets.UTF_8))
            .getAsJsonObject();
        if (!"net.fabricmc.fabric.mixin.content.registry".equals(config.get("package").getAsString())) {
            diagnostics.add("unexpected Fabric mixin package");
        }
        JsonArray mixins = config.getAsJsonArray("mixins");
        int context = 0;
        int walk = 0;
        int blockStateBase = 0;
        for (var element : mixins) {
            if ("PathfindingContextMixin".equals(element.getAsString())) context++;
            if ("WalkNodeEvaluatorMixin".equals(element.getAsString())) walk++;
            if ("BlockBehaviourBlockStateBaseMixin".equals(element.getAsString())) blockStateBase++;
        }
        if (context != 1 || walk != 1 || blockStateBase != 1) {
            diagnostics.add("expected exactly one context, Walk, and BlockStateBase mixin declaration");
        }
    }

    /**
     * This exact Fabric mixin is structural only: it adds one refresher interface/method and does
     * not inject into or overwrite a method that a worker reads. The full module/class hashes above
     * pin the implementation; this shape check makes the safety argument explicit and fail-closed.
     */
    private static void verifyStructuralBlockStateMixin(byte[] bytes, List<String> diagnostics) {
        ClassNode node = classNode(bytes);
        AnnotationNode mixin = findAnnotation(node.visibleAnnotations, node.invisibleAnnotations, MIXIN_DESC);
        Object targets = annotationValue(mixin, "value");
        if (!(targets instanceof List<?> values) || values.size() != 1
                || !(values.get(0) instanceof Type type)
                || !BLOCK_STATE_BASE_INTERNAL.equals(type.getInternalName())) {
            diagnostics.add("Fabric BlockStateBase mixin target shape drift");
        }
        if (!node.interfaces.equals(List.of(RANDOM_TICK_REFRESHER_INTERNAL))) {
            diagnostics.add("Fabric BlockStateBase mixin interface shape drift: " + node.interfaces);
        }

        int addedMethods = 0;
        for (MethodNode method : node.methods) {
            boolean shadow = findAnnotation(method.visibleAnnotations, method.invisibleAnnotations,
                SHADOW_DESC) != null;
            for (AnnotationNode annotation : annotations(method)) {
                if (annotation.desc.startsWith("Lorg/spongepowered/asm/mixin/injection/")
                        || OVERWRITE_DESC.equals(annotation.desc)) {
                    diagnostics.add("Fabric BlockStateBase mixin unexpectedly modifies an existing method: "
                        + method.name + method.desc + " via " + annotation.desc);
                }
            }
            if (!method.name.equals("<init>") && !shadow) {
                addedMethods++;
                if (!method.name.equals("fabric_api$refreshRandomTickCache")
                        || !method.desc.equals("()V")) {
                    diagnostics.add("unexpected Fabric BlockStateBase merged method: "
                        + method.name + method.desc);
                }
            }
        }
        if (addedMethods != 1) {
            diagnostics.add("Fabric BlockStateBase mixin must add exactly one method, found "
                + addedMethods);
        }
    }

    private static void verifyLandRegistry(byte[] bytes, List<String> diagnostics) {
        ClassNode node = classNode(bytes);
        Set<String> fields = new HashSet<>();
        node.fields.forEach(field -> fields.add(field.name + field.desc));
        if (!fields.equals(Set.of("LOGGERLorg/slf4j/Logger;", "PATH_TYPESLjava/util/Map;"))) {
            diagnostics.add("LandPathTypeRegistry field shape drift: " + fields);
        }
        Set<String> expectedMethods = Set.of(
            "<init>()V",
            "register(Lnet/minecraft/world/level/block/Block;Lnet/minecraft/world/level/pathfinder/PathType;Lnet/minecraft/world/level/pathfinder/PathType;)V",
            "register(Lnet/minecraft/world/level/block/Block;Lnet/fabricmc/fabric/api/registry/LandPathTypeRegistry$StaticPathTypeProvider;)V",
            "registerDynamic(Lnet/minecraft/world/level/block/Block;Lnet/fabricmc/fabric/api/registry/LandPathTypeRegistry$DynamicPathTypeProvider;)V",
            "getPathType(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Z)Lnet/minecraft/world/level/pathfinder/PathType;",
            "getPathTypeProvider(Lnet/minecraft/world/level/block/Block;)Lnet/fabricmc/fabric/api/registry/LandPathTypeRegistry$PathTypeProvider;",
            "lambda$register$0(Lnet/minecraft/world/level/pathfinder/PathType;Lnet/minecraft/world/level/pathfinder/PathType;Lnet/minecraft/world/level/block/state/BlockState;Z)Lnet/minecraft/world/level/pathfinder/PathType;",
            "<clinit>()V");
        Set<String> actualMethods = new HashSet<>();
        int mapPuts = 0;
        int providerReads = 0;
        int simpleDelegations = 0;
        for (MethodNode method : node.methods) {
            actualMethods.add(method.name + method.desc);
            int methodPuts = 0;
            int methodGets = 0;
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call) {
                    if (call.owner.equals("java/util/Map") && call.name.equals("put")) {
                        mapPuts++;
                        methodPuts++;
                    }
                    if (call.owner.equals("java/util/Map") && call.name.equals("get")) {
                        methodGets++;
                    }
                    if (method.name.equals("register")
                            && method.desc.equals("(Lnet/minecraft/world/level/block/Block;Lnet/minecraft/world/level/pathfinder/PathType;Lnet/minecraft/world/level/pathfinder/PathType;)V")
                            && call.owner.equals(REGISTRY_INTERNAL) && call.name.equals("register")
                            && call.desc.equals("(Lnet/minecraft/world/level/block/Block;Lnet/fabricmc/fabric/api/registry/LandPathTypeRegistry$StaticPathTypeProvider;)V")) {
                        simpleDelegations++;
                    }
                }
            }
            if ((method.name.equals("register")
                    && method.desc.contains("StaticPathTypeProvider"))
                    || method.name.equals("registerDynamic")) {
                if (methodPuts != 1) diagnostics.add("registration mutation-site drift: "
                    + method.name + method.desc + " puts=" + methodPuts);
            }
            if (method.name.equals("getPathTypeProvider")) providerReads += methodGets;
        }
        if (!actualMethods.equals(expectedMethods)) {
            diagnostics.add("LandPathTypeRegistry method shape drift: " + actualMethods);
        }
        if (mapPuts != 2 || simpleDelegations != 1 || providerReads != 1) {
            diagnostics.add("LandPathTypeRegistry lifecycle shape drift: puts=" + mapPuts
                + " simpleDelegations=" + simpleDelegations + " providerReads=" + providerReads);
        }
    }

    private static void verifyFabricContextMixin(byte[] bytes, Set<String> injected,
                                                  List<String> diagnostics) {
        ClassNode node = classNode(bytes);
        AnnotationNode mixin = findAnnotation(node.visibleAnnotations, node.invisibleAnnotations, MIXIN_DESC);
        Object targets = annotationValue(mixin, "value");
        if (!(targets instanceof List<?> values) || values.size() != 1
                || !(values.get(0) instanceof Type type)
                || !CONTEXT_INTERNAL.equals(type.getInternalName())) {
            diagnostics.add("Fabric context mixin target shape drift");
        }
        int injectionAnnotations = 0;
        int registryCalls = 0;
        for (MethodNode method : node.methods) {
            for (AnnotationNode annotation : annotations(method)) {
                if (annotation.desc.startsWith("Lorg/spongepowered/asm/mixin/injection/")) {
                    injectionAnnotations++;
                    if (!INJECT_DESC.equals(annotation.desc)) {
                        diagnostics.add("unexpected Fabric injection annotation " + annotation.desc);
                        continue;
                    }
                    Object targetMethod = annotationValue(annotation, "method");
                    if (!annotationContains(targetMethod, "getPathTypeFromState")) {
                        diagnostics.add("Fabric context injection targets unexpected method " + targetMethod);
                    } else {
                        injected.add("getPathTypeFromState(III)Lnet/minecraft/world/level/pathfinder/PathType;");
                    }
                    if (!Boolean.TRUE.equals(annotationValue(annotation, "cancellable"))) {
                        diagnostics.add("Fabric context injection is no longer cancellable");
                    }
                    Object at = annotationValue(annotation, "at");
                    if (!(at instanceof List<?> list) || list.size() != 1
                            || !(list.get(0) instanceof AnnotationNode atNode)
                            || !"INVOKE_ASSIGN".equals(annotationValue(atNode, "value"))
                            || !"Lnet/minecraft/core/BlockPos$MutableBlockPos;set(III)Lnet/minecraft/core/BlockPos$MutableBlockPos;"
                                .equals(annotationValue(atNode, "target"))) {
                        diagnostics.add("Fabric context injection point shape drift");
                    }
                }
            }
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call && REGISTRY_INTERNAL.equals(call.owner)) {
                    registryCalls++;
                    if (!"getPathType".equals(call.name)) {
                        diagnostics.add("unexpected LandPathTypeRegistry call " + call.name + call.desc);
                    }
                }
            }
        }
        if (injectionAnnotations != 1 || injected.size() != 1) {
            diagnostics.add("Fabric PathfindingContextMixin must contain exactly one injection");
        }
        if (registryCalls != 1) {
            diagnostics.add("Fabric PathfindingContextMixin must contain exactly one registry lookup");
        }
    }

    private static void verifySwimRoute(byte[] swim, byte[] nodeEvaluator, byte[] pathFinder,
                                        byte[] pathContext, Set<String> swimCalls,
                                        List<String> diagnostics) {
        collectContextCalls("SwimNodeEvaluator", swim, swimCalls, diagnostics);
        Set<String> inheritedCalls = new HashSet<>();
        collectContextCalls("NodeEvaluator", nodeEvaluator, inheritedCalls, diagnostics);
        collectContextCalls("PathFinder", pathFinder, inheritedCalls, diagnostics);
        if (swimCalls.contains("getPathTypeFromState(III)Lnet/minecraft/world/level/pathfinder/PathType;")) {
            diagnostics.add("Swim directly reaches Fabric-injected getPathTypeFromState");
        }
        if (inheritedCalls.contains("getPathTypeFromState(III)Lnet/minecraft/world/level/pathfinder/PathType;")) {
            diagnostics.add("Swim route reaches Fabric-injected getPathTypeFromState through shared search code");
        }
        if (!ALLOWED_SWIM_CONTEXT_CALLS.equals(swimCalls)) {
            diagnostics.add("exact Swim PathfindingContext call shape drift: " + swimCalls);
        }
        ClassNode contextNode = classNode(pathContext);
        long injectedTargetCount = contextNode.methods.stream()
            .filter(m -> m.name.equals("getPathTypeFromState")
                && m.desc.equals("(III)Lnet/minecraft/world/level/pathfinder/PathType;"))
            .count();
        if (injectedTargetCount != 1) {
            diagnostics.add("vanilla PathfindingContext injected target shape drift");
        }
    }

    private static void collectContextCalls(String source, byte[] bytes, Set<String> calls,
                                            List<String> diagnostics) {
        ClassNode node = classNode(bytes);
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call) {
                    if (REGISTRY_INTERNAL.equals(call.owner)) {
                        diagnostics.add(source + " contains a direct land-provider lookup: "
                            + call.name + call.desc);
                    }
                    if (CONTEXT_INTERNAL.equals(call.owner)) {
                        String signature = call.name + call.desc;
                        calls.add(signature);
                        if (source.equals("SwimNodeEvaluator")
                                && !ALLOWED_SWIM_CONTEXT_CALLS.contains(signature)) {
                            diagnostics.add("unexpected Swim PathfindingContext call: " + signature);
                        }
                    }
                }
            }
        }
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
                                                 List<AnnotationNode> invisible,
                                                 String descriptor) {
        if (visible != null) for (AnnotationNode node : visible) if (descriptor.equals(node.desc)) return node;
        if (invisible != null) for (AnnotationNode node : invisible) if (descriptor.equals(node.desc)) return node;
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
        if (value instanceof List<?> list) return list.size() == 1 && expected.equals(list.get(0));
        return false;
    }

    private static void checkHash(String label, byte[] bytes, String expected,
                                  List<String> diagnostics) {
        String actual = sha256(bytes);
        if (!expected.equals(actual)) diagnostics.add(label + " hash mismatch: " + actual);
    }

    private static byte[] readModuleArtifact(ModContainer module) throws IOException {
        ModOrigin origin = module.getOrigin();
        if (origin.getKind() == ModOrigin.Kind.NESTED) {
            ModContainer parent = module.getContainingMod()
                .orElseThrow(() -> new IOException("nested module has no containing mod"));
            String location = origin.getParentSubLocation();
            Path nested = parent.findPath(location)
                .orElseThrow(() -> new IOException("nested module artifact missing: " + location));
            return Files.readAllBytes(nested);
        }
        if (origin.getKind() == ModOrigin.Kind.PATH) {
            List<Path> regular = origin.getPaths().stream().filter(Files::isRegularFile).toList();
            if (regular.size() != 1) {
                throw new IOException("module PATH origin must provide exactly one regular artifact");
            }
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
        String binaryFileName = type.getName().substring(type.getPackageName().length() + 1)
            + ".class";
        try (InputStream in = type.getResourceAsStream(binaryFileName)) {
            if (in == null) throw new IOException("class resource missing: " + type.getName());
            return in.readAllBytes();
        }
    }

    private static ForeignMixinScanner.SwimExemptionEvidence invalidEvidence(String reason) {
        return ForeignMixinScanner.SwimExemptionEvidence.unverified(reason);
    }

    private static String sha256(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
