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

    /**
     * The audit's verdict must match the artifact this build actually resolved -- in BOTH directions.
     *
     * <p>It used to assert only that the bundle passes, which is true on 26.1.2 and false on 26.2,
     * where the dependency resolves a newer ServerCore the audit has never inspected. That made this
     * fail on the port branch from the day it existed, and 0.6.1+26.2 was published with it red.
     * Pinning both arms is stronger than the original and true on either branch: the pinned artifact
     * certifies with its exact shape proof, and anything else REFUSES for the stated reason.
     */
    @Test void theAuditCertifiesExactlyThePinnedServerCoreAndRefusesAnythingElse() throws Exception {
        var jar = jarContaining(SERVERCORE_MIXIN);
        var result = AuditedMixinCompatibility.verifyServerCore(serverCoreBundle());
        if (AuditedMixinCompatibility.SERVERCORE_VERSION.equals(ResolvedArtifact.version(jar))) {
            assertTrue(result.valid(), () -> String.join("\n", result.diagnostics()));
            assertEquals(3, result.modifiedMethods().size());
            assertTrue(result.modifiedMethods().stream().allMatch(s -> s.startsWith("findPath(")));
        } else {
            assertFalse(result.valid(),
                "an unpinned ServerCore must not be certified; this build resolved "
                    + ResolvedArtifact.version(jar));
            assertTrue(result.diagnostics().stream().anyMatch(d -> d.contains("hash mismatch")),
                () -> "the refusal must name which pinned artifact drifted, not just fail: "
                    + result.diagnostics());
        }
    }

    @Test void everyServerCoreFingerprintPartFailsClosedOnDrift() throws Exception {
        var exact = serverCoreBundle();
        byte[][] parts = {exact.moduleJar(), exact.config(), exact.fabricConfig(), exact.mixin(),
            exact.plugin(), exact.vanillaTarget()};
        for (int changed = 0; changed < parts.length; changed++) {
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

    @Test void theAuditCertifiesExactlyThePinnedRabbitAndRefusesAnythingElse() throws Exception {
        var jar = jarContaining(RABBIT_MIXIN);
        var result = AuditedMixinCompatibility.verifyRabbit(rabbitBundle());
        if (AuditedMixinCompatibility.RABBIT_VERSION.equals(ResolvedArtifact.version(jar))) {
            assertTrue(result.valid(), () -> String.join("\n", result.diagnostics()));
            assertEquals(java.util.Set.of(
                "doStuckDetection(Lnet/minecraft/world/phys/Vec3;)V",
                "resetStuckTimeout()V"), result.modifiedMethods());
        } else {
            assertFalse(result.valid(),
                "an unpinned rabbit-pathfinding-fix must not be certified; this build resolved "
                    + ResolvedArtifact.version(jar));
            assertTrue(result.diagnostics().stream().anyMatch(d -> d.contains("hash mismatch")),
                () -> "the refusal must name which pinned artifact drifted, not just fail: "
                    + result.diagnostics());
        }
    }

    /**
     * The version of the artifact this build actually resolved.
     *
     * <p>These tests read whatever jar is on the classpath, and the branches resolve different ones:
     * 26.1.2 gets the pinned artifact, 26.2 gets a newer one the audit has never seen.
     */
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

    /**
     * The published table must quote the hashes the CODE pins.
     *
     * <p>It used to hash whichever jar this build resolved, which asserts something about Gradle's
     * dependency resolution rather than about the document, and is unsatisfiable on any branch that
     * resolves a different artifact. What the document can actually get wrong is drifting from the
     * constants, so that is what is checked.
     *
     * <p>The other half of the chain is {@link
     * #theAuditCertifiesExactlyThePinnedServerCoreAndRefusesAnythingElse}: on a branch that resolves
     * the pinned artifact, the bundle verifies, and verifying includes hashing the jar against these
     * same constants. Document equals constant here, constant equals jar there.
     */
    @Test void publishedTableCarriesExactArtifactHashesAndDriftBoundary() throws Exception {
        String table = Files.readString(Path.of("COMPATIBILITY.md"));
        assertTrue(table.contains(AuditedMixinCompatibility.SERVERCORE_MODULE_SHA),
            "COMPATIBILITY.md no longer quotes the pinned ServerCore jar hash the audit enforces");
        assertTrue(table.contains(AuditedMixinCompatibility.RABBIT_MODULE_SHA),
            "COMPATIBILITY.md no longer quotes the pinned rabbit jar hash the audit enforces");
        assertTrue(table.contains("changed version, byte, mixin selector, target descriptor"));
        assertTrue(table.contains("fails closed"));
    }

    @Test void everyRabbitFingerprintPartFailsClosedOnDrift() throws Exception {
        var exact = rabbitBundle();
        byte[][] parts = {exact.moduleJar(), exact.config(), exact.mixin(), exact.vanillaTarget(),
            exact.workerEntry()};
        for (int changed = 0; changed < parts.length; changed++) {
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
