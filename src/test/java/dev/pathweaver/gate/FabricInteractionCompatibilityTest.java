package dev.pathweaver.gate;

import net.fabricmc.fabric.api.event.player.BlockEvents;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.pathfinder.NodeEvaluator;
import net.minecraft.world.level.pathfinder.PathFinder;
import net.minecraft.world.level.pathfinder.PathTypeCache;
import net.minecraft.world.level.pathfinder.PathfindingContext;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

class FabricInteractionCompatibilityTest {
    private static final String USE_ITEM_ON = "useItemOn(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/InteractionHand;Lnet/minecraft/world/phys/BlockHitResult;)Lnet/minecraft/world/InteractionResult;";
    private static final String USE_WITHOUT_ITEM = "useWithoutItem(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/phys/BlockHitResult;)Lnet/minecraft/world/InteractionResult;";

    @Test void exactBundleHasOnlyTwoUnreachableInteractionInjectors() throws Exception {
        FabricInteractionCompatibility.Verification result =
            FabricInteractionCompatibility.verifyBundle(exactBundle());
        assertTrue(result.valid(), () -> String.join("\n", result.diagnostics()));
        assertEquals(Set.of(USE_ITEM_ON, USE_WITHOUT_ITEM), result.injectedTargets());
        assertFalse(result.workerBlockStateCalls().contains(USE_ITEM_ON));
        assertFalse(result.workerBlockStateCalls().contains(USE_WITHOUT_ITEM));
    }

    @Test void everyPinnedArtifactOrClassByteDriftFailsClosed() throws Exception {
        FabricInteractionCompatibility.Bundle exact = exactBundle();
        byte[][] parts = { exact.moduleJar(), exact.config(), exact.mixin(), exact.blockStateBase(),
            exact.pathFinder(), exact.nodeEvaluator(), exact.walkNodeEvaluator(), exact.pathContext(),
            exact.pathTypeCache(), exact.pathRegion() };
        for (int changed = 0; changed < parts.length; changed++) {
            byte[][] copy = Arrays.stream(parts).map(byte[]::clone).toArray(byte[][]::new);
            copy[changed][copy[changed].length - 1] ^= 1;
            var result = FabricInteractionCompatibility.verifyBundle(new FabricInteractionCompatibility.Bundle(
                copy[0], copy[1], copy[2], copy[3], copy[4], copy[5], copy[6], copy[7], copy[8], copy[9]));
            assertFalse(result.valid(), "resource " + changed + " drift must deny");
            assertTrue(result.diagnostics().stream().anyMatch(s -> s.contains("hash mismatch")));
        }
    }

    @Test void changedSelectorAndAddedInjectorBytesFailClosed() throws Exception {
        FabricInteractionCompatibility.Bundle exact = exactBundle();
        var selectorResult = FabricInteractionCompatibility.verifyBundle(
            copyWithMixin(exact, changeFirstSelector(exact.mixin())));
        var injectorResult = FabricInteractionCompatibility.verifyBundle(
            copyWithMixin(exact, addUnexpectedInjector(exact.mixin())));

        assertFalse(selectorResult.valid());
        assertTrue(selectorResult.diagnostics().stream().anyMatch(s ->
            s.contains("hash mismatch") || s.contains("selector")));
        assertFalse(injectorResult.valid());
        assertTrue(injectorResult.diagnostics().stream().anyMatch(s ->
            s.contains("hash mismatch") || s.contains("method shape")));
    }

    private static FabricInteractionCompatibility.Bundle exactBundle() throws Exception {
        Path module = Path.of(new URI(BlockEvents.class.getProtectionDomain()
            .getCodeSource().getLocation().toString()));
        assertTrue(Files.isRegularFile(module));
        try (ZipFile zip = new ZipFile(module.toFile())) {
            return new FabricInteractionCompatibility.Bundle(Files.readAllBytes(module),
                zipBytes(zip, "fabric-events-interaction-v0.mixins.json"),
                zipBytes(zip, "net/fabricmc/fabric/mixin/event/interaction/BlockBehaviourBlockStateBaseMixin.class"),
                classBytes(BlockBehaviour.BlockStateBase.class), classBytes(PathFinder.class),
                classBytes(NodeEvaluator.class), classBytes(WalkNodeEvaluator.class),
                classBytes(PathfindingContext.class), classBytes(PathTypeCache.class),
                classBytes(PathNavigationRegion.class));
        }
    }

    private static byte[] zipBytes(ZipFile zip, String entryName) throws Exception {
        var entry = zip.getEntry(entryName);
        assertNotNull(entry, entryName);
        try (InputStream in = zip.getInputStream(entry)) { return in.readAllBytes(); }
    }

    private static byte[] classBytes(Class<?> type) throws Exception {
        String name = type.getName().substring(type.getPackageName().length() + 1) + ".class";
        try (InputStream in = type.getResourceAsStream(name)) {
            assertNotNull(in, type.getName());
            return in.readAllBytes();
        }
    }

    private static FabricInteractionCompatibility.Bundle copyWithMixin(
            FabricInteractionCompatibility.Bundle b, byte[] mixin) {
        return new FabricInteractionCompatibility.Bundle(b.moduleJar(), b.config(), mixin,
            b.blockStateBase(), b.pathFinder(), b.nodeEvaluator(), b.walkNodeEvaluator(),
            b.pathContext(), b.pathTypeCache(), b.pathRegion());
    }

    private static byte[] changeFirstSelector(byte[] bytes) {
        ClassNode node = classNode(bytes);
        outer: for (MethodNode method : node.methods) {
            if (method.invisibleAnnotations == null) continue;
            for (AnnotationNode annotation : method.invisibleAnnotations) {
                if (!annotation.desc.equals("Lorg/spongepowered/asm/mixin/injection/Inject;")) continue;
                for (int i = 0; i < annotation.values.size(); i += 2) {
                    if ("method".equals(annotation.values.get(i))) {
                        annotation.values.set(i + 1, new ArrayList<>(List.of("changedSelector")));
                        break outer;
                    }
                }
            }
        }
        return bytes(node);
    }

    private static byte[] addUnexpectedInjector(byte[] bytes) {
        ClassNode node = classNode(bytes);
        MethodNode method = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
            "addedInjector", "(Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V",
            null, null);
        AnnotationNode at = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/At;");
        at.values = new ArrayList<>(List.of("value", "HEAD"));
        AnnotationNode inject = new AnnotationNode(
            "Lorg/spongepowered/asm/mixin/injection/Inject;");
        inject.values = new ArrayList<>(List.of("method", new ArrayList<>(List.of("useItemOn")),
            "at", new ArrayList<>(List.of(at)), "cancellable", true));
        method.invisibleAnnotations = new ArrayList<>(List.of(inject));
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        node.methods.add(method);
        return bytes(node);
    }

    private static ClassNode classNode(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static byte[] bytes(ClassNode node) {
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }
}
