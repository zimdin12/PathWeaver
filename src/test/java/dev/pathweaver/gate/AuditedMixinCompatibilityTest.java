package dev.pathweaver.gate;

import dev.pathweaver.async.PathWorkerPool;
import dev.pathweaver.mixin.PathNavigationMixin;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.pathfinder.PathFinder;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.io.InputStream;
import java.net.JarURLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

class AuditedMixinCompatibilityTest {
    private static final String SERVERCORE_MIXIN =
        "me/wesley1808/servercore/mixin/optimizations/misc/PathFinderMixin.class";
    private static final String SERVERCORE_PLUGIN =
        "me/wesley1808/servercore/mixin/ServerCoreMixinPlugin.class";
    private static final String RABBIT_MIXIN =
        "net/litetex/rpf/mixin/EntityNavigationMixin.class";

    @Test void exactServerCoreBundlePassesHashAndAsmShapeProof() throws Exception {
        var result = AuditedMixinCompatibility.verifyServerCore(serverCoreBundle());
        assertTrue(result.valid(), () -> String.join("\n", result.diagnostics()));
        assertEquals(3, result.modifiedMethods().size());
        assertTrue(result.modifiedMethods().stream().allMatch(s -> s.startsWith("findPath(")));
    }

    /**
     * Every AUDITED CLASS must still fail closed on a single flipped bit — but not the module jar.
     *
     * <p>0.6 stopped checking the whole-jar hash. It moves whenever the mod is rebuilt for any
     * reason, while saying nothing about whether the audited code changed, and it was the thing
     * switching PathWeaver off on ordinary updates: a real 221-mod pack shipped Lithium 0.24.5
     * against a 0.24.6 pin, every family was denied, and all fifteen of Lithium's pathfinding
     * classes were byte-identical between the two releases.
     *
     * <p>The per-class hashes are what the audit actually rests on and they are unchanged, so this
     * test still drives a bit-flip through each of them. Index 0 -- the module jar -- is excluded
     * deliberately, and that exclusion is the policy change made visible rather than a weakening
     * that slipped through.
     */
    @Test void everyServerCoreFingerprintPartFailsClosedOnDrift() throws Exception {
        var exact = serverCoreBundle();
        byte[][] parts = {exact.moduleJar(), exact.config(), exact.fabricConfig(), exact.mixin(),
            exact.plugin(), exact.vanillaTarget()};
        for (int changed = 1; changed < parts.length; changed++) {  // 0 = module jar, no longer pinned
            byte[][] copy = Arrays.stream(parts).map(byte[]::clone).toArray(byte[][]::new);
            copy[changed][copy[changed].length - 1] ^= 1;
            var result = AuditedMixinCompatibility.verifyServerCore(
                new AuditedMixinCompatibility.ServerCoreBundle(
                    copy[0], copy[1], copy[2], copy[3], copy[4], copy[5]));
            assertFalse(result.valid(), "resource " + changed + " drift must deny");
            assertTrue(result.diagnostics().stream().anyMatch(s -> s.contains("hash mismatch")),
                () -> "missing hash diagnostic: " + result.diagnostics());
        }
    }

    @Test void exactRabbitBundlePassesMethodNonReachabilityShapeProof() throws Exception {
        var result = AuditedMixinCompatibility.verifyRabbit(rabbitBundle());
        assertTrue(result.valid(), () -> String.join("\n", result.diagnostics()));
        assertEquals(java.util.Set.of(
            "doStuckDetection(Lnet/minecraft/world/phys/Vec3;)V",
            "resetStuckTimeout()V"), result.modifiedMethods());
    }

    @Test void pathWeaverWorkerCallableReachesOnlyThePinnedSearchClosure() throws Exception {
        ClassNode pool = new ClassNode();
        new ClassReader(classBytes(PathWorkerPool.class)).accept(pool, 0);
        int callableCalls = 0;
        for (var method : pool.methods) {
            for (var instruction : method.instructions) {
                if (!(instruction instanceof MethodInsnNode call)) continue;
                assertNotEquals("net/minecraft/world/entity/ai/navigation/PathNavigation", call.owner,
                    () -> "worker pool directly reaches Rabbit owner from " + method.name + method.desc);
                if (call.owner.equals("java/util/concurrent/Callable") && call.name.equals("call")
                        && call.desc.equals("()Ljava/lang/Object;")) callableCalls++;
            }
        }
        assertEquals(1, callableCalls, "worker must enter exactly one submitted search callable");

        ClassNode routing = new ClassNode();
        new ClassReader(classBytes(PathNavigationMixin.class)).accept(routing, 0);
        int findPathCalls = 0;
        for (var method : routing.methods) {
            boolean searchClosure = false;
            for (var instruction : method.instructions) {
                if (!(instruction instanceof MethodInsnNode call)) continue;
                if (call.owner.equals("net/minecraft/world/level/pathfinder/PathFinder")
                        && call.name.equals("findPath")
                        && call.desc.equals("(Lnet/minecraft/world/level/PathNavigationRegion;"
                            + "Lnet/minecraft/world/entity/Mob;Ljava/util/Set;FIF)"
                            + "Lnet/minecraft/world/level/pathfinder/Path;")) {
                    findPathCalls++;
                    searchClosure = true;
                }
            }
            if (searchClosure) {
                for (var instruction : method.instructions) {
                    if (instruction instanceof MethodInsnNode call) {
                        assertNotEquals("net/minecraft/world/entity/ai/navigation/PathNavigation",
                            call.owner, () -> "submitted search closure reaches Rabbit target owner: "
                                + method.name + method.desc);
                    }
                }
            }
        }
        assertEquals(1, findPathCalls,
            "worker route must have exactly one pinned PathFinder.findPath entry");
    }

    @Test void pathOriginRequiresExactlyOneRegularArtifact() throws Exception {
        Path dir = Files.createTempDirectory("pathweaver-audit-origin");
        try {
            Path one = Files.write(dir.resolve("one.jar"), new byte[] {1});
            Path two = Files.write(dir.resolve("two.jar"), new byte[] {2});
            assertEquals(one,
                AuditedMixinCompatibility.singleRegularArtifact(List.of(dir, one), "audit"));
            assertThrows(java.io.IOException.class,
                () -> AuditedMixinCompatibility.singleRegularArtifact(List.of(dir), "audit"));
            assertThrows(java.io.IOException.class,
                () -> AuditedMixinCompatibility.singleRegularArtifact(List.of(one, two), "audit"));
        } finally {
            try (var files = Files.list(dir)) {
                for (Path path : files.toList()) Files.deleteIfExists(path);
            }
            Files.deleteIfExists(dir);
        }
    }

    @Test void publishedTableCarriesExactArtifactHashesAndDriftBoundary() throws Exception {
        String table = Files.readString(Path.of("COMPATIBILITY.md"));
        assertTrue(table.contains(sha256(serverCoreBundle().moduleJar())));
        assertTrue(table.contains(sha256(rabbitBundle().moduleJar())));
        assertTrue(table.contains("changed version, byte, mixin selector, target descriptor"));
        assertTrue(table.contains("fails closed"));
    }

    /**
     * Every AUDITED CLASS must still fail closed on a single flipped bit — but not the module jar.
     *
     * <p>0.6 stopped checking the whole-jar hash. It moves whenever the mod is rebuilt for any
     * reason, while saying nothing about whether the audited code changed, and it was the thing
     * switching PathWeaver off on ordinary updates: a real 221-mod pack shipped Lithium 0.24.5
     * against a 0.24.6 pin, every family was denied, and all fifteen of Lithium's pathfinding
     * classes were byte-identical between the two releases.
     *
     * <p>The per-class hashes are what the audit actually rests on and they are unchanged, so this
     * test still drives a bit-flip through each of them. Index 0 -- the module jar -- is excluded
     * deliberately, and that exclusion is the policy change made visible rather than a weakening
     * that slipped through.
     */
    @Test void everyRabbitFingerprintPartFailsClosedOnDrift() throws Exception {
        var exact = rabbitBundle();
        byte[][] parts = {exact.moduleJar(), exact.config(), exact.mixin(), exact.vanillaTarget(),
            exact.workerEntry()};
        for (int changed = 1; changed < parts.length; changed++) {  // 0 = module jar, no longer pinned
            byte[][] copy = Arrays.stream(parts).map(byte[]::clone).toArray(byte[][]::new);
            copy[changed][copy[changed].length - 1] ^= 1;
            var result = AuditedMixinCompatibility.verifyRabbit(
                new AuditedMixinCompatibility.RabbitBundle(
                    copy[0], copy[1], copy[2], copy[3], copy[4]));
            assertFalse(result.valid(), "resource " + changed + " drift must deny");
            assertTrue(result.diagnostics().stream().anyMatch(s -> s.contains("hash mismatch")),
                () -> "missing hash diagnostic: " + result.diagnostics());
        }
    }

    private static AuditedMixinCompatibility.ServerCoreBundle serverCoreBundle() throws Exception {
        Path jar = jarContaining(SERVERCORE_MIXIN);
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            return new AuditedMixinCompatibility.ServerCoreBundle(
                Files.readAllBytes(jar), zipBytes(zip, "servercore.common.mixins.json"),
                zipBytes(zip, "servercore.fabric.mixins.json"),
                zipBytes(zip, SERVERCORE_MIXIN), zipBytes(zip, SERVERCORE_PLUGIN),
                classBytes(PathFinder.class));
        }
    }

    private static AuditedMixinCompatibility.RabbitBundle rabbitBundle() throws Exception {
        Path jar = jarContaining(RABBIT_MIXIN);
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            return new AuditedMixinCompatibility.RabbitBundle(
                Files.readAllBytes(jar), zipBytes(zip, "rabbit-pathfinding-fix.mixins.json"),
                zipBytes(zip, RABBIT_MIXIN), classBytes(PathNavigation.class),
                classBytes(PathFinder.class));
        }
    }

    private static Path jarContaining(String resource) throws Exception {
        var url = AuditedMixinCompatibilityTest.class.getClassLoader().getResource(resource);
        assertNotNull(url, "exact audited dependency missing: " + resource);
        assertEquals("jar", url.getProtocol());
        return Path.of(((JarURLConnection) url.openConnection()).getJarFileURL().toURI());
    }

    private static byte[] zipBytes(ZipFile zip, String name) throws Exception {
        var entry = zip.getEntry(name);
        assertNotNull(entry, name);
        try (InputStream in = zip.getInputStream(entry)) {
            return in.readAllBytes();
        }
    }

    private static byte[] classBytes(Class<?> type) throws Exception {
        String name = type.getName().substring(type.getPackageName().length() + 1) + ".class";
        try (InputStream in = type.getResourceAsStream(name)) {
            assertNotNull(in, type.getName());
            return in.readAllBytes();
        }
    }

    private static String sha256(byte[] bytes) throws Exception {
        return java.util.HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
