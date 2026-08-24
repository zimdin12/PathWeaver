package dev.pathweaver.gate;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModOrigin;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.pathfinder.PathFinder;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

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

/** Exact runtime fingerprints and ASM proofs for audited foreign pathfinding mixins. */
final class AuditedMixinCompatibility {
    static final String SERVERCORE_ID = "servercore";
    static final String SERVERCORE_VERSION = "1.5.19+26.1.2";
    static final String SERVERCORE_CONFIG = "servercore.common.mixins.json";
    private static final String SERVERCORE_FABRIC_CONFIG = "servercore.fabric.mixins.json";
    static final String SERVERCORE_MIXIN =
        "me.wesley1808.servercore.mixin.optimizations.misc.PathFinderMixin";
    static final String SERVERCORE_PLUGIN =
        "me.wesley1808.servercore.mixin.ServerCoreMixinPlugin";
    static final String PATH_FINDER = "net.minecraft.world.level.pathfinder.PathFinder";

    static final String RABBIT_ID = "rabbit-pathfinding-fix";
    static final String RABBIT_VERSION = "1.3.0";
    static final String RABBIT_CONFIG = "rabbit-pathfinding-fix.mixins.json";
    static final String RABBIT_MIXIN = "net.litetex.rpf.mixin.EntityNavigationMixin";
    static final String PATH_NAVIGATION =
        "net.minecraft.world.entity.ai.navigation.PathNavigation";

    private static final String SERVERCORE_MODULE_SHA =
        "593941ef360ba493b180c213bbb093d95223dba4a34d97e7559b914847363aa4";
    private static final String SERVERCORE_CONFIG_SHA =
        "39a5120066542578e74e3775a880d14f04bee935e2d6764132cdf3f7d7af82a7";
    private static final String SERVERCORE_FABRIC_CONFIG_SHA =
        "93b73019559e3c40245fc684d3d4e1b06049362ae3eaa5db53b179807a014a9f";
    private static final String SERVERCORE_MIXIN_SHA =
        "ff0e986419f4685469063772c85e477810dfe425bf33a1ad1a62ed65ac6aefa7";
    static final String SERVERCORE_PLUGIN_SHA =
        "0e6ddc8d3c66c7e5826831845e0da41f6594b758a128d207419083b081e33cf6";
    private static final String PATH_FINDER_SHA =
        "095d620eaac37aa71af017858682e89689039a3b999cf2a5fcfce3f1c3973b2c";
    private static final String RABBIT_MODULE_SHA =
        "6388f7a83b303c7de485f5f0089bd7e887ea45f9adf6bc9b099cad932fa58851";
    private static final String RABBIT_CONFIG_SHA =
        "4adce45f270e2890686cd403392fdb81f1450024ff6814df04e51c57ec49fde6";
    private static final String RABBIT_MIXIN_SHA =
        "bb31e6819c0d00216c9f2841849beff0ce5234f298d804876a91f7e5b225926b";
    private static final String PATH_NAVIGATION_SHA =
        "ecfbf40003f91522f8cb99da84ff4ab9e4891e9511808412421fc640be7b339e";

    private static final String MIXIN_DESC = "Lorg/spongepowered/asm/mixin/Mixin;";
    private static final String REDIRECT_DESC =
        "Lorg/spongepowered/asm/mixin/injection/Redirect;";
    private static final String INJECT_DESC = "Lorg/spongepowered/asm/mixin/injection/Inject;";
    private static final String FIND_PATH_DESC =
        "(Lnet/minecraft/world/level/PathNavigationRegion;Lnet/minecraft/world/entity/Mob;"
            + "Ljava/util/Set;FIF)Lnet/minecraft/world/level/pathfinder/Path;";
    private static final String FIND_PATH_SELECTOR = "findPath" + FIND_PATH_DESC;
    private static final String DO_STUCK_DESC = "(Lnet/minecraft/world/phys/Vec3;)V";
    private static final Set<String> SERVERCORE_REDIRECT_TARGETS = Set.of(
        "Ljava/util/Set;stream()Ljava/util/stream/Stream;",
        "Ljava/util/stream/Collectors;toMap(Ljava/util/function/Function;"
            + "Ljava/util/function/Function;)Ljava/util/stream/Collector;",
        "Ljava/util/stream/Stream;collect(Ljava/util/stream/Collector;)Ljava/lang/Object;"
    );
    private static final Set<String> SERVERCORE_BODY_CALLS = Set.of(
        "java/util/Set.size()I", "java/util/Set.iterator()Ljava/util/Iterator;",
        "java/util/Iterator.hasNext()Z", "java/util/Iterator.next()Ljava/lang/Object;",
        "net/minecraft/core/BlockPos.getX()I", "net/minecraft/core/BlockPos.getY()I",
        "net/minecraft/core/BlockPos.getZ()I",
        "net/minecraft/world/level/pathfinder/NodeEvaluator.getTarget(DDD)"
            + "Lnet/minecraft/world/level/pathfinder/Target;",
        "it/unimi/dsi/fastutil/objects/Object2ObjectOpenHashMap.<init>(I)V",
        "it/unimi/dsi/fastutil/objects/Object2ObjectOpenHashMap.put"
            + "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"
    );

    private AuditedMixinCompatibility() {}

    record ServerCoreBundle(byte[] moduleJar, byte[] config, byte[] fabricConfig, byte[] mixin,
                            byte[] plugin, byte[] vanillaTarget) {}
    record RabbitBundle(byte[] moduleJar, byte[] config, byte[] mixin, byte[] vanillaTarget,
                        byte[] workerEntry) {}
    record Verification(boolean valid, List<String> diagnostics, Set<String> modifiedMethods) {
        Verification {
            diagnostics = List.copyOf(diagnostics);
            modifiedMethods = Set.copyOf(modifiedMethods);
        }
    }

    static ForeignMixinScanner.AuditedExemptionEvidence inspectRuntime(
            FabricLoader loader, ModContainer module) {
        List<String> diagnostics = new ArrayList<>();
        try {
            String id = module.getMetadata().getId();
            String version = module.getMetadata().getVersion().getFriendlyString();
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
            if (SERVERCORE_ID.equals(id)) {
                if (!SERVERCORE_VERSION.equals(version)) return unverified(id, "unsupported version " + version);
                Verification result = verifyServerCore(new ServerCoreBundle(
                    readModuleArtifact(module), readModResource(module, SERVERCORE_CONFIG),
                    readModResource(module, SERVERCORE_FABRIC_CONFIG),
                    readModResource(module, classResource(SERVERCORE_MIXIN)),
                    readModResource(module, classResource(SERVERCORE_PLUGIN)),
                    readClassBytes(PathFinder.class)));
                diagnostics.addAll(result.diagnostics());
                return result.valid() ? exactServerCoreEvidence()
                    : new ForeignMixinScanner.AuditedExemptionEvidence(Set.of(), diagnostics);
            }
            if (RABBIT_ID.equals(id)) {
                if (!RABBIT_VERSION.equals(version)) return unverified(id, "unsupported version " + version);
                Verification result = verifyRabbit(new RabbitBundle(
                    readModuleArtifact(module), readModResource(module, RABBIT_CONFIG),
                    readModResource(module, classResource(RABBIT_MIXIN)),
                    readClassBytes(PathNavigation.class), readClassBytes(PathFinder.class)));
                diagnostics.addAll(result.diagnostics());
                return result.valid() ? exactRabbitEvidence()
                    : new ForeignMixinScanner.AuditedExemptionEvidence(Set.of(), diagnostics);
            }
            return unverified(id, "module is not an audited compatibility owner");
        } catch (Throwable t) {
            diagnostics.add("exact audited compatibility fingerprint failed: " + t);
            return new ForeignMixinScanner.AuditedExemptionEvidence(Set.of(), diagnostics);
        }
    }

    static ForeignMixinScanner.AuditedExemptionEvidence exactServerCoreEvidence() {
        return ForeignMixinScanner.AuditedExemptionEvidence.verified(new ForeignMixinScanner.AuditKey(
            SERVERCORE_ID, SERVERCORE_VERSION, SERVERCORE_CONFIG, SERVERCORE_MIXIN, PATH_FINDER,
            new ForeignMixinScanner.PluginIdentity(SERVERCORE_PLUGIN, SERVERCORE_PLUGIN_SHA)));
    }

    static ForeignMixinScanner.AuditedExemptionEvidence exactRabbitEvidence() {
        return ForeignMixinScanner.AuditedExemptionEvidence.verified(new ForeignMixinScanner.AuditKey(
            RABBIT_ID, RABBIT_VERSION, RABBIT_CONFIG, RABBIT_MIXIN, PATH_NAVIGATION, null));
    }

    static Verification verifyServerCore(ServerCoreBundle bundle) {
        List<String> diagnostics = new ArrayList<>();
        Set<String> modified = new HashSet<>();
        checkHash("ServerCore module jar", bundle.moduleJar(), SERVERCORE_MODULE_SHA, diagnostics);
        checkHash("ServerCore mixin config", bundle.config(), SERVERCORE_CONFIG_SHA, diagnostics);
        checkHash("ServerCore Fabric mixin config", bundle.fabricConfig(),
            SERVERCORE_FABRIC_CONFIG_SHA, diagnostics);
        checkHash("ServerCore PathFinderMixin", bundle.mixin(), SERVERCORE_MIXIN_SHA, diagnostics);
        checkHash("ServerCore plugin", bundle.plugin(), SERVERCORE_PLUGIN_SHA, diagnostics);
        checkHash("vanilla PathFinder", bundle.vanillaTarget(), PATH_FINDER_SHA, diagnostics);
        try {
            verifyConfig(bundle.config(), "me.wesley1808.servercore.mixin", SERVERCORE_PLUGIN,
                "optimizations.misc.PathFinderMixin", diagnostics);
            verifyConfig(bundle.fabricConfig(), "me.wesley1808.servercore.fabric.mixin",
                SERVERCORE_PLUGIN, "optimizations.misc.BlockGetterMixin", diagnostics);
            verifyServerCoreMixin(bundle.mixin(), modified, diagnostics);
            verifyServerCorePlugin(bundle.plugin(), diagnostics);
            requireVanillaMethod(bundle.vanillaTarget(), "findPath", FIND_PATH_DESC, diagnostics);
        } catch (Throwable t) {
            diagnostics.add("ServerCore ASM/config shape parse failed: " + t);
        }
        return new Verification(diagnostics.isEmpty(), diagnostics, modified);
    }

    static Verification verifyRabbit(RabbitBundle bundle) {
        List<String> diagnostics = new ArrayList<>();
        Set<String> modified = new HashSet<>();
        checkHash("rabbit module jar", bundle.moduleJar(), RABBIT_MODULE_SHA, diagnostics);
        checkHash("rabbit mixin config", bundle.config(), RABBIT_CONFIG_SHA, diagnostics);
        checkHash("rabbit EntityNavigationMixin", bundle.mixin(), RABBIT_MIXIN_SHA, diagnostics);
        checkHash("vanilla PathNavigation", bundle.vanillaTarget(), PATH_NAVIGATION_SHA, diagnostics);
        checkHash("vanilla worker PathFinder", bundle.workerEntry(), PATH_FINDER_SHA, diagnostics);
        try {
            verifyConfig(bundle.config(), "net.litetex.rpf.mixin", null,
                "EntityNavigationMixin", diagnostics);
            verifyRabbitMixin(bundle.mixin(), modified, diagnostics);
            requireVanillaMethod(bundle.vanillaTarget(), "doStuckDetection", DO_STUCK_DESC, diagnostics);
            requireVanillaMethod(bundle.vanillaTarget(), "resetStuckTimeout", "()V", diagnostics);
            verifyRabbitTargetsNotReachableFromPathFinder(bundle.workerEntry(), diagnostics);
        } catch (Throwable t) {
            diagnostics.add("rabbit ASM/config shape parse failed: " + t);
        }
        return new Verification(diagnostics.isEmpty(), diagnostics, modified);
    }

    private static void verifyServerCoreMixin(byte[] bytes, Set<String> modified,
                                              List<String> diagnostics) {
        ClassNode node = classNode(bytes);
        requireMixinTarget(node, "net/minecraft/world/level/pathfinder/PathFinder", diagnostics);
        Set<String> redirects = new HashSet<>();
        int redirectCount = 0;
        for (MethodNode method : node.methods) {
            AnnotationNode redirect = findAnnotation(method, REDIRECT_DESC);
            if (redirect == null) continue;
            redirectCount++;
            if (!annotationContains(annotationValue(redirect, "method"), FIND_PATH_SELECTOR)) {
                diagnostics.add("ServerCore redirect selector drift: " + method.name + method.desc);
            }
            AnnotationNode at = singleAnnotation(annotationValue(redirect, "at"));
            String target = at == null ? null : (String) annotationValue(at, "target");
            if (at == null || !"INVOKE".equals(annotationValue(at, "value"))
                    || !SERVERCORE_REDIRECT_TARGETS.contains(target)) {
                diagnostics.add("ServerCore redirect site drift: " + method.name + method.desc);
            } else {
                redirects.add(target);
                modified.add(FIND_PATH_SELECTOR + " <- " + target);
            }
            verifyServerCoreHandlerBody(method, diagnostics);
        }
        if (redirectCount != 3 || !redirects.equals(SERVERCORE_REDIRECT_TARGETS)) {
            diagnostics.add("ServerCore must supply exactly the three audited findPath redirects: "
                + redirects);
        }
    }

    private static void verifyServerCoreHandlerBody(MethodNode method, List<String> diagnostics) {
        List<Integer> opcodes = realOpcodes(method);
        if (method.desc.equals("(Ljava/util/Set;)Ljava/util/stream/Stream;")
                || method.desc.equals("(Ljava/util/function/Function;Ljava/util/function/Function;)"
                    + "Ljava/util/stream/Collector;")) {
            if (!opcodes.equals(List.of(Opcodes.ACONST_NULL, Opcodes.ARETURN))) {
                diagnostics.add("ServerCore null redirect body drift: " + method.name + method.desc);
            }
            return;
        }
        String mapHandler = "(Ljava/util/stream/Stream;Ljava/util/stream/Collector;"
            + "Lnet/minecraft/world/level/PathNavigationRegion;Lnet/minecraft/world/entity/Mob;"
            + "Ljava/util/Set;)Ljava/lang/Object;";
        if (!method.desc.equals(mapHandler)) {
            diagnostics.add("unexpected ServerCore redirect handler descriptor: " + method.desc);
            return;
        }
        Set<String> calls = new HashSet<>();
        Set<String> fieldReads = new HashSet<>();
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call) {
                calls.add(call.owner + "." + call.name + call.desc);
            } else if (insn instanceof FieldInsnNode field) {
                if (field.getOpcode() == Opcodes.GETFIELD) {
                    fieldReads.add(field.owner + "." + field.name + ":" + field.desc);
                } else {
                    diagnostics.add("ServerCore map handler performs a shared field write: "
                        + field.owner + "." + field.name);
                }
            } else if (insn instanceof TypeInsnNode type && type.getOpcode() == Opcodes.NEW
                    && !"it/unimi/dsi/fastutil/objects/Object2ObjectOpenHashMap".equals(type.desc)) {
                diagnostics.add("ServerCore map handler allocates unexpected type: " + type.desc);
            }
        }
        if (!calls.equals(SERVERCORE_BODY_CALLS)) {
            diagnostics.add("ServerCore map handler call shape drift: " + calls);
        }
        Set<String> expectedFieldReads = Set.of(
            "me/wesley1808/servercore/mixin/optimizations/misc/PathFinderMixin.nodeEvaluator:"
                + "Lnet/minecraft/world/level/pathfinder/NodeEvaluator;");
        if (!fieldReads.equals(expectedFieldReads)) {
            diagnostics.add("ServerCore map handler field-read shape drift: " + fieldReads);
        }
    }

    private static void verifyServerCorePlugin(byte[] bytes, List<String> diagnostics) {
        ClassNode node = classNode(bytes);
        for (String signature : List.of(
                "acceptTargets(Ljava/util/Set;Ljava/util/Set;)V",
                "preApply(Ljava/lang/String;Lorg/objectweb/asm/tree/ClassNode;Ljava/lang/String;"
                    + "Lorg/spongepowered/asm/mixin/extensibility/IMixinInfo;)V",
                "postApply(Ljava/lang/String;Lorg/objectweb/asm/tree/ClassNode;Ljava/lang/String;"
                    + "Lorg/spongepowered/asm/mixin/extensibility/IMixinInfo;)V")) {
            MethodNode method = method(node, signature);
            if (method == null || !realOpcodes(method).equals(List.of(Opcodes.RETURN))) {
                diagnostics.add("ServerCore plugin callback is not inert: " + signature);
            }
        }
        MethodNode getMixins = method(node, "getMixins()Ljava/util/List;");
        if (getMixins == null || !realOpcodes(getMixins).equals(
                List.of(Opcodes.ACONST_NULL, Opcodes.ARETURN))) {
            diagnostics.add("ServerCore plugin dynamically contributes mixins");
        }
    }

    private static void verifyRabbitMixin(byte[] bytes, Set<String> modified,
                                          List<String> diagnostics) {
        ClassNode node = classNode(bytes);
        requireMixinTarget(node, "net/minecraft/world/entity/ai/navigation/PathNavigation", diagnostics);
        int injections = 0;
        for (MethodNode method : node.methods) {
            AnnotationNode inject = findAnnotation(method, INJECT_DESC);
            if (inject == null) continue;
            injections++;
            AnnotationNode at = singleAnnotation(annotationValue(inject, "at"));
            Object selector = annotationValue(inject, "method");
            if (annotationContains(selector, "doStuckDetection")
                    && method.desc.equals("(Lnet/minecraft/world/phys/Vec3;"
                        + "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V")) {
                if (at == null || !"INVOKE".equals(annotationValue(at, "value"))
                        || !"Lnet/minecraft/world/level/pathfinder/Path;getNextNodePos()"
                            .concat("Lnet/minecraft/core/BlockPos;")
                            .equals(annotationValue(at, "target"))
                        || !Boolean.TRUE.equals(annotationValue(inject, "cancellable"))) {
                    diagnostics.add("rabbit doStuckDetection injection shape drift");
                }
                modified.add("doStuckDetection" + DO_STUCK_DESC);
            } else if (annotationContains(selector, "resetStuckTimeout")
                    && method.desc.equals("(Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V")) {
                if (at == null || !"TAIL".equals(annotationValue(at, "value"))) {
                    diagnostics.add("rabbit resetStuckTimeout injection shape drift");
                }
                modified.add("resetStuckTimeout()V");
            } else {
                diagnostics.add("unexpected rabbit navigation injection: " + method.name + method.desc);
            }
        }
        if (injections != 2 || modified.size() != 2) {
            diagnostics.add("rabbit navigation mixin must modify exactly two audited methods");
        }
    }

    /** The worker enters the pinned PathFinder.findPath overload. It has no call edge into
     * PathNavigation, so Rabbit's two navigation-maintenance injections are unreachable on workers. */
    static void verifyRabbitTargetsNotReachableFromPathFinder(byte[] pathFinder,
                                                                      List<String> diagnostics) {
        ClassNode node = classNode(pathFinder);
        requireVanillaMethod(pathFinder, "findPath", FIND_PATH_DESC, diagnostics);
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call
                        && "net/minecraft/world/entity/ai/navigation/PathNavigation".equals(call.owner)) {
                    diagnostics.add("worker PathFinder unexpectedly reaches PathNavigation: "
                        + method.name + method.desc + " -> " + call.name + call.desc);
                }
            }
        }
    }

    static void verifyConfig(byte[] bytes, String expectedPackage, String expectedPlugin,
                                     String mixinName, List<String> diagnostics) {
        JsonObject config = JsonParser.parseString(
            new String(bytes, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
        if (!expectedPackage.equals(config.get("package").getAsString())) {
            diagnostics.add("mixin package drift");
        }
        String actualPlugin = config.has("plugin") ? config.get("plugin").getAsString() : null;
        if (!java.util.Objects.equals(expectedPlugin, actualPlugin)) diagnostics.add("plugin declaration drift");
        JsonArray mixins = config.getAsJsonArray("mixins");
        int occurrences = 0;
        for (var element : mixins) if (mixinName.equals(element.getAsString())) occurrences++;
        if (occurrences != 1) diagnostics.add("expected exactly one " + mixinName + " declaration");
        if (!config.get("required").getAsBoolean()
                || config.getAsJsonObject("injectors").get("defaultRequire").getAsInt() != 1) {
            diagnostics.add("required/defaultRequire fail-closed shape drift");
        }
    }

    static void requireMixinTarget(ClassNode node, String internal,
                                           List<String> diagnostics) {
        AnnotationNode mixin = findAnnotation(node.visibleAnnotations, node.invisibleAnnotations,
            MIXIN_DESC);
        Object targets = annotationValue(mixin, "value");
        if (!(targets instanceof List<?> values) || values.size() != 1
                || !(values.get(0) instanceof Type type)
                || !internal.equals(type.getInternalName())) {
            diagnostics.add("mixin target shape drift: " + internal);
        }
    }

    private static void requireVanillaMethod(byte[] bytes, String name, String desc,
                                             List<String> diagnostics) {
        long count = classNode(bytes).methods.stream()
            .filter(m -> m.name.equals(name) && m.desc.equals(desc)).count();
        if (count != 1) diagnostics.add("vanilla target method descriptor drift: " + name + desc);
    }

    static ClassNode classNode(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static MethodNode method(ClassNode node, String signature) {
        return node.methods.stream().filter(m -> (m.name + m.desc).equals(signature))
            .findFirst().orElse(null);
    }

    private static List<Integer> realOpcodes(MethodNode method) {
        List<Integer> result = new ArrayList<>();
        for (AbstractInsnNode insn : method.instructions) {
            if (insn.getOpcode() >= 0) result.add(insn.getOpcode());
        }
        return result;
    }

    private static AnnotationNode findAnnotation(MethodNode method, String descriptor) {
        return findAnnotation(method.visibleAnnotations, method.invisibleAnnotations, descriptor);
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
        return value instanceof List<?> list && list.size() == 1 && expected.equals(list.get(0));
    }

    private static AnnotationNode singleAnnotation(Object value) {
        if (value instanceof AnnotationNode annotation) return annotation;
        if (value instanceof List<?> list && list.size() == 1 && list.get(0) instanceof AnnotationNode annotation) {
            return annotation;
        }
        return null;
    }

    static void checkHash(String label, byte[] bytes, String expected,
                                  List<String> diagnostics) {
        String actual = sha256(bytes);
        if (!expected.equals(actual)) diagnostics.add(label + " hash mismatch: " + actual);
    }

    static String classResource(String className) {
        return className.replace('.', '/') + ".class";
    }

    static byte[] readModuleArtifact(ModContainer module) throws IOException {
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
            return Files.readAllBytes(singleRegularArtifact(origin.getPaths(),
                module.getMetadata().getId()));
        }
        throw new IOException("unsupported module origin: " + origin.getKind());
    }

    static Path singleRegularArtifact(List<Path> paths, String moduleId) throws IOException {
        List<Path> regular = paths.stream().filter(Files::isRegularFile).toList();
        if (regular.size() != 1) {
            throw new IOException("expected exactly one artifact path for "
                + moduleId + ", found " + regular.size());
        }
        return regular.getFirst();
    }

    static byte[] readModResource(ModContainer module, String resource) throws IOException {
        Path path = module.findPath(resource)
            .orElseThrow(() -> new IOException("module resource missing: " + resource));
        return Files.readAllBytes(path);
    }

    static byte[] readClassBytes(Class<?> type) throws IOException {
        String name = type.getName().substring(type.getPackageName().length() + 1) + ".class";
        try (InputStream in = type.getResourceAsStream(name)) {
            if (in == null) throw new IOException("class resource missing: " + type.getName());
            return in.readAllBytes();
        }
    }

    private static ForeignMixinScanner.AuditedExemptionEvidence unverified(String id, String reason) {
        return ForeignMixinScanner.AuditedExemptionEvidence.unverified(id + " exact audit: " + reason);
    }

    static String sha256(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
