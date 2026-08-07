package dev.pathweaver.gate;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Diagonal Blocks exemption turns on one claim: that its {@code isDiagonalValid} override never
 * reaches {@code StarCollisionBlock}, whose corner-shape caches are unsynchronized fastutil maps
 * mutated lazily. If that check cannot actually fail, the exemption is decoration.
 */
class DiagonalBlocksCompatibilityTest {
    private static final String STAR =
        "fuzs/diagonalblocks/common/api/v2/block/StarCollisionBlock";

    @Test
    void rejectsAnOverrideThatReachesTheShapeCacheOwner() {
        List<String> diagnostics = new java.util.ArrayList<>();
        DiagonalBlocksCompatibility.verifyOverrideTouchesNoMutableSharedState(
            overrideCalling(STAR, "getShape"), diagnostics);
        assertTrue(diagnostics.stream().anyMatch(d -> d.contains("shape-cache owner")),
            diagnostics.toString());
    }

    @Test
    void rejectsAnOverrideThatReadsUnauditedSharedState() {
        List<String> diagnostics = new java.util.ArrayList<>();
        DiagonalBlocksCompatibility.verifyOverrideTouchesNoMutableSharedState(
            overrideReadingStatic(STAR, "CORNER_SHAPES_CACHE"), diagnostics);
        assertTrue(diagnostics.stream().anyMatch(d -> d.contains("unaudited shared state")),
            diagnostics.toString());
    }

    @Test
    void acceptsAReadOfTheImmutablePropertyMap() {
        List<String> diagnostics = new java.util.ArrayList<>();
        DiagonalBlocksCompatibility.verifyOverrideTouchesNoMutableSharedState(
            overrideReadingStatic(STAR, "PROPERTY_BY_DIRECTION"), diagnostics);
        assertTrue(diagnostics.stream().noneMatch(
                d -> d.contains("unaudited shared state") || d.contains("shape-cache owner")),
            diagnostics.toString());
    }

    @Test
    void rejectsAnOverrideThatWritesSharedState() {
        List<String> diagnostics = new java.util.ArrayList<>();
        DiagonalBlocksCompatibility.verifyOverrideTouchesNoMutableSharedState(
            overrideWritingStatic(STAR, "CORNER_SHAPES_CACHE"), diagnostics);
        assertTrue(diagnostics.stream().anyMatch(d -> d.contains("writes shared state")),
            diagnostics.toString());
    }

    /** Synthetic bundles carry no real artifact, so the hash pins must still be reported. */
    @Test
    void hashPinsAreEnforcedIndependentlyOfTheShapeProof() {
        List<String> diagnostics = DiagonalBlocksCompatibility.verify(
            new DiagonalBlocksCompatibility.Bundle(new byte[0], new byte[0],
                overrideReadingStatic(STAR, "PROPERTY_BY_DIRECTION")));
        // Two, not three: the module jar is no longer pinned as of 0.6, because its hash moves on
        // any unrelated edit while proving nothing about the audited classes. The config and the
        // WalkNodeEvaluator mixin are still pinned and still fail closed, which is the evidence the
        // audit rests on.
        assertEquals(2, diagnostics.stream().filter(d -> d.contains("hash mismatch")).count(),
            diagnostics.toString());
    }

    private static byte[] overrideCalling(String owner, String method) {
        ClassWriter writer = probe();
        MethodVisitor visitor = body(writer);
        visitor.visitMethodInsn(Opcodes.INVOKESTATIC, owner, method, "()V", false);
        return finish(writer, visitor);
    }

    private static byte[] overrideReadingStatic(String owner, String field) {
        ClassWriter writer = probe();
        MethodVisitor visitor = body(writer);
        visitor.visitFieldInsn(Opcodes.GETSTATIC, owner, field, "Ljava/util/Map;");
        visitor.visitInsn(Opcodes.POP);
        return finish(writer, visitor);
    }

    private static byte[] overrideWritingStatic(String owner, String field) {
        ClassWriter writer = probe();
        MethodVisitor visitor = body(writer);
        visitor.visitInsn(Opcodes.ACONST_NULL);
        visitor.visitFieldInsn(Opcodes.PUTSTATIC, owner, field, "Ljava/util/Map;");
        return finish(writer, visitor);
    }

    private static ClassWriter probe() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "dev/pathweaver/DiagProbe", null,
            "java/lang/Object", null);
        return writer;
    }

    private static MethodVisitor body(ClassWriter writer) {
        MethodVisitor visitor = writer.visitMethod(Opcodes.ACC_PUBLIC, "isDiagonalValid", "()V",
            null, null);
        visitor.visitCode();
        return visitor;
    }

    private static byte[] finish(ClassWriter writer, MethodVisitor visitor) {
        visitor.visitInsn(Opcodes.RETURN);
        visitor.visitMaxs(2, 1);
        visitor.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }
}
