package dev.pathweaver.gate;

import dev.pathweaver.config.CompatibilityTier;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Lithium exemption rests on an ASM proof that nothing a worker executes writes shared state.
 * A detector that silently passes everything would make that proof worthless while still reading
 * like evidence, so these tests drive it from both sides with synthesized bytecode.
 */
class LithiumPathfindingCompatibilityTest {

    @Test
    void flagsAFieldWriteOutsideInitialization() {
        List<String> diagnostics = new ArrayList<>();
        LithiumPathfindingCompatibility.requireWritesConfinedTo("probe",
            classWritingFieldIn("onSearchPath"), Set.of("<init>", "<clinit>"), diagnostics);
        assertEquals(1, diagnostics.size(), diagnostics.toString());
        assertTrue(diagnostics.getFirst().contains("onSearchPath"), diagnostics.toString());
    }

    @Test
    void acceptsTheSameWriteWhenItIsAnAllowedInitializer() {
        List<String> diagnostics = new ArrayList<>();
        LithiumPathfindingCompatibility.requireWritesConfinedTo("probe",
            classWritingFieldIn("lithium$initializePathNodeTypeCache"),
            Set.of("<init>", "<clinit>", "lithium$initializePathNodeTypeCache"), diagnostics);
        assertTrue(diagnostics.isEmpty(), diagnostics.toString());
    }

    @Test
    void flagsALazyInitializerCallFromTheSearchPath() {
        List<String> diagnostics = new ArrayList<>();
        LithiumPathfindingCompatibility.requireInitializerConfined(
            classCalling("lithium$initializePathNodeTypeCache"), diagnostics);
        assertEquals(1, diagnostics.size(), diagnostics.toString());
        assertTrue(diagnostics.getFirst().contains("worker could trigger a write"),
            diagnostics.toString());
    }

    @Test
    void acceptsAClassThatNeverCallsTheInitializer() {
        List<String> diagnostics = new ArrayList<>();
        LithiumPathfindingCompatibility.requireInitializerConfined(
            classCalling("someUnrelatedHelper"), diagnostics);
        assertTrue(diagnostics.isEmpty(), diagnostics.toString());
    }

    /**
     * Every Lithium claim that would otherwise deny an evaluator needs its own key. A missing key
     * denies (fail-closed, merely useless); a key for a claim we never audited would exempt
     * something unproven, so the set is asserted exactly rather than by size.
     */
    @Test
    void exactEvidenceCoversEveryDenyingClaimAndNothingElse() {
        Set<String> covered = LithiumPathfindingCompatibility.exactEvidence().verified().stream()
            .map(key -> key.mixinClass() + " -> " + key.target())
            .collect(java.util.stream.Collectors.toSet());
        assertEquals(Set.of(
            LithiumPathfindingCompatibility.BLOCK_STATE_MIXIN + " -> "
                + LithiumPathfindingCompatibility.BLOCK_STATE_BASE,
            LithiumPathfindingCompatibility.WALK_MIXIN + " -> "
                + LithiumPathfindingCompatibility.WALK,
            LithiumPathfindingCompatibility.REGION_MIXIN + " -> "
                + LithiumPathfindingCompatibility.PATH_REGION,
            LithiumPathfindingCompatibility.CONTEXT_MIXIN + " -> "
                + LithiumPathfindingCompatibility.PATH_CONTEXT,
            LithiumPathfindingCompatibility.CONTEXT_ACCESSOR + " -> "
                + LithiumPathfindingCompatibility.PATH_CONTEXT,
            // Lithium's sensitive surface is not limited to its ai.pathing package; these three
            // live elsewhere and were found by the live scan, not by reading the package name.
            LithiumPathfindingCompatibility.FLAGS_MIXIN + " -> "
                + LithiumPathfindingCompatibility.BLOCK_STATE_BASE,
            LithiumPathfindingCompatibility.CHUNK_REGION_MIXIN + " -> "
                + LithiumPathfindingCompatibility.PATH_REGION,
            LithiumPathfindingCompatibility.NAVIGATION_MIXIN + " -> "
                + LithiumPathfindingCompatibility.PATH_NAVIGATION), covered);
    }

    @Test
    void everyEvidenceKeyPinsTheAuditedPluginIdentity() {
        LithiumPathfindingCompatibility.exactEvidence().verified().forEach(key -> {
            assertEquals(LithiumPathfindingCompatibility.PLUGIN, key.pluginIdentity().className());
            assertEquals(LithiumPathfindingCompatibility.PLUGIN_SHA,
                key.pluginIdentity().classSha256());
        });
    }

    @Test
    void tierOrderingIsStrictlyIncreasing() {
        assertFalse(CompatibilityTier.STRICT.allowsAudited());
        assertFalse(CompatibilityTier.STRICT.bypassesScan());
        assertTrue(CompatibilityTier.AUDITED.allowsAudited());
        assertFalse(CompatibilityTier.AUDITED.bypassesScan(),
            "AUDITED must still enforce the scan for everything it has not audited");
        assertTrue(CompatibilityTier.ALL.allowsAudited());
        assertTrue(CompatibilityTier.ALL.bypassesScan());
    }

    private static byte[] classWritingFieldIn(String methodName) {
        ClassWriter writer = probeClass();
        writer.visitField(Opcodes.ACC_PRIVATE, "cached", "Ljava/lang/Object;", null, null)
            .visitEnd();
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, methodName, "()V", null, null);
        method.visitCode();
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitInsn(Opcodes.ACONST_NULL);
        method.visitFieldInsn(Opcodes.PUTFIELD, "dev/pathweaver/Probe", "cached",
            "Ljava/lang/Object;");
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(2, 1);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] classCalling(String calleeName) {
        ClassWriter writer = probeClass();
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, "onSearchPath", "()V",
            null, null);
        method.visitCode();
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "dev/pathweaver/Probe", calleeName, "()V",
            false);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(1, 1);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static ClassWriter probeClass() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "dev/pathweaver/Probe", null,
            "java/lang/Object", null);
        return writer;
    }
}
