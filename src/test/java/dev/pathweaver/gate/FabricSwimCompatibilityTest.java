package dev.pathweaver.gate;

import net.fabricmc.fabric.api.registry.LandPathTypeRegistry;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.pathfinder.NodeEvaluator;
import net.minecraft.world.level.pathfinder.PathFinder;
import net.minecraft.world.level.pathfinder.PathfindingContext;
import net.minecraft.world.level.pathfinder.SwimNodeEvaluator;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

class FabricSwimCompatibilityTest {
    @Test void exactAuditedBundleEnumeratesOneFabricInjectionAndNoSwimReachability() throws Exception {
        FabricSwimCompatibility.Verification result =
            FabricSwimCompatibility.verifyBundle(exactBundle());

        assertTrue(result.valid(), () -> String.join("\n", result.diagnostics()));
        assertEquals(Set.of(
            "getPathTypeFromState(III)Lnet/minecraft/world/level/pathfinder/PathType;"),
            result.fabricInjectedMethods());
        assertEquals(Set.of(
            "getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;",
            "level()Lnet/minecraft/world/level/CollisionGetter;"), result.swimContextCalls());
    }

    @Test void anyCriticalResourceHashDriftFailsClosed() throws Exception {
        FabricSwimCompatibility.Bundle exact = exactBundle();
        byte[][] parts = {
            exact.moduleJar(), exact.config(), exact.contextMixin(), exact.walkMixin(),
            exact.blockStateBaseMixin(), exact.swim(), exact.nodeEvaluator(), exact.pathFinder(),
            exact.pathContext(), exact.blockStateBase()
        };
        for (int changed = 0; changed < parts.length; changed++) {
            int changedIndex = changed;
            byte[][] copy = Arrays.stream(parts).map(byte[]::clone).toArray(byte[][]::new);
            copy[changedIndex][copy[changedIndex].length - 1] ^= 1;
            FabricSwimCompatibility.Verification result = FabricSwimCompatibility.verifyBundle(
                new FabricSwimCompatibility.Bundle(copy[0], copy[1], copy[2], copy[3],
                    copy[4], copy[5], copy[6], copy[7], copy[8], copy[9]));
            assertFalse(result.valid(), "resource " + changedIndex + " drift must fail closed");
            assertTrue(result.diagnostics().stream().anyMatch(s -> s.contains("hash mismatch")),
                () -> "missing hash diagnostic for resource " + changedIndex + ": " + result.diagnostics());
        }
    }

    private static FabricSwimCompatibility.Bundle exactBundle() throws Exception {
        Path module = Path.of(new URI(LandPathTypeRegistry.class.getProtectionDomain()
            .getCodeSource().getLocation().toString()));
        assertTrue(Files.isRegularFile(module), "test must use the exact Fabric module artifact");
        try (ZipFile zip = new ZipFile(module.toFile())) {
            return new FabricSwimCompatibility.Bundle(
                Files.readAllBytes(module),
                zipBytes(zip, "fabric-content-registries-v0.mixins.json"),
                zipBytes(zip,
                    "net/fabricmc/fabric/mixin/content/registry/PathfindingContextMixin.class"),
                zipBytes(zip,
                    "net/fabricmc/fabric/mixin/content/registry/WalkNodeEvaluatorMixin.class"),
                zipBytes(zip,
                    "net/fabricmc/fabric/mixin/content/registry/BlockBehaviourBlockStateBaseMixin.class"),
                classBytes(SwimNodeEvaluator.class), classBytes(NodeEvaluator.class),
                classBytes(PathFinder.class), classBytes(PathfindingContext.class),
                classBytes(BlockBehaviour.BlockStateBase.class));
        }
    }

    private static byte[] zipBytes(ZipFile zip, String entryName) throws Exception {
        var entry = zip.getEntry(entryName);
        assertNotNull(entry, entryName);
        try (InputStream in = zip.getInputStream(entry)) {
            return in.readAllBytes();
        }
    }

    private static byte[] classBytes(Class<?> type) throws Exception {
        String binaryFileName = type.getName().substring(type.getPackageName().length() + 1)
            + ".class";
        try (InputStream in = type.getResourceAsStream(binaryFileName)) {
            assertNotNull(in, type.getName());
            return in.readAllBytes();
        }
    }
}
