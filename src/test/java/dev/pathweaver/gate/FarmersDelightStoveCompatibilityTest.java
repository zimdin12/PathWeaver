package dev.pathweaver.gate;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The audit's value is entirely in what it refuses, so each proof obligation is checked by building a
 * class that violates exactly that one obligation and asserting the audit rejects it.
 */
class FarmersDelightStoveCompatibilityTest {
    private static final String HOST =
        "vectorwing/farmersdelight/common/block/AbstractStoveBlock";
    private static final String PROVIDER =
        "net/fabricmc/fabric/api/registry/LandPathTypeRegistry$DynamicPathTypeProvider";
    private static final String LAMBDA_DESC =
        "(Lnet/minecraft/world/level/block/state/BlockState;"
            + "Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Z)"
            + "Lnet/minecraft/world/level/pathfinder/PathType;";
    private static final String DECIDER_DESC =
        "(Lnet/minecraft/world/level/block/state/BlockState;"
            + "Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;"
            + "Lnet/minecraft/world/entity/Mob;)Lnet/minecraft/world/level/pathfinder/PathType;";

    @Test void acceptsTheAuditedForwardingShape() {
        List<String> diagnostics = new ArrayList<>();
        FarmersDelightStoveCompatibility.verifySingleForwardingProvider(
            host(1, true, false), diagnostics);
        assertEquals(List.of(), diagnostics);
    }

    @Test void rejectsMoreThanOneProviderLambda() {
        List<String> diagnostics = new ArrayList<>();
        FarmersDelightStoveCompatibility.verifySingleForwardingProvider(
            host(2, true, false), diagnostics);
        assertEquals(1, diagnostics.size(), diagnostics.toString());
        assertTrue(diagnostics.get(0).contains("exactly one provider lambda"), diagnostics.toString());
    }

    @Test void rejectsNoProviderLambdaAtAll() {
        List<String> diagnostics = new ArrayList<>();
        FarmersDelightStoveCompatibility.verifySingleForwardingProvider(
            host(0, true, false), diagnostics);
        assertEquals(1, diagnostics.size(), diagnostics.toString());
    }

    @Test void rejectsALambdaThatDoesMoreThanForward() {
        // The forwarding lambda must not compute anything itself; an extra call could read the world
        // it was handed without the decider ever being involved.
        List<String> diagnostics = new ArrayList<>();
        FarmersDelightStoveCompatibility.verifySingleForwardingProvider(
            host(1, true, true), diagnostics);
        assertTrue(diagnostics.stream().anyMatch(d -> d.contains("beyond forwarding")),
            diagnostics.toString());
    }

    @Test void rejectsAnIndyPointingAtSomeOtherMethod() {
        List<String> diagnostics = new ArrayList<>();
        FarmersDelightStoveCompatibility.verifySingleForwardingProvider(
            host(1, false, false), diagnostics);
        assertTrue(diagnostics.stream().anyMatch(d -> d.contains("not the audited method")),
            diagnostics.toString());
    }

    @Test void acceptsADeciderThatReadsOnlyTheBlockState() {
        List<String> diagnostics = new ArrayList<>();
        FarmersDelightStoveCompatibility.verifyDeciderIgnoresWorldAndPosition(
            decider(-1), diagnostics);
        assertEquals(List.of(), diagnostics);
    }

    @Test void rejectsADeciderThatTouchesTheWorldOrThePosition() {
        // This is the load-bearing proof: slot 2 is the BlockGetter, slot 3 the BlockPos.
        for (int slot : new int[] {2, 3}) {
            List<String> diagnostics = new ArrayList<>();
            FarmersDelightStoveCompatibility.verifyDeciderIgnoresWorldAndPosition(
                decider(slot), diagnostics);
            assertTrue(diagnostics.stream().anyMatch(d -> d.contains("local slot " + slot)),
                "slot " + slot + " must be refused: " + diagnostics);
        }
    }

    @Test void acceptsAJarDeclaringTheDeciderExactlyOnce() {
        List<String> diagnostics = new ArrayList<>();
        FarmersDelightStoveCompatibility.verifyNoOtherDeciderInJar(
            jar(false, false), diagnostics);
        assertEquals(List.of(), diagnostics);
    }

    @Test void rejectsASubclassOverrideInsideTheJar() {
        // The decider is invoked virtually, so a second implementation could be dispatched to.
        List<String> diagnostics = new ArrayList<>();
        FarmersDelightStoveCompatibility.verifyNoOtherDeciderInJar(
            jar(true, false), diagnostics);
        assertTrue(diagnostics.stream().anyMatch(d -> d.contains("exactly one implementation")),
            diagnostics.toString());
    }

    @Test void rejectsANestedJarThatWasNeverAudited() {
        // A nested jar could carry an override this scan never sees.
        List<String> diagnostics = new ArrayList<>();
        FarmersDelightStoveCompatibility.verifyNoOtherDeciderInJar(
            jar(false, true), diagnostics);
        assertTrue(diagnostics.stream().anyMatch(d -> d.contains("nested jars")),
            diagnostics.toString());
    }

    @Test void onlyRecognisesLambdasHostedByTheAuditedClass() {
        assertFalse(FarmersDelightStoveCompatibility.isAuditedProvider(null));
        assertFalse(FarmersDelightStoveCompatibility.isAuditedProvider("not a lambda"));
        assertFalse(FarmersDelightStoveCompatibility.isAuditedProvider(new Object()));
    }

    /**
     * A host class with a configurable number of provider lambdas.
     *
     * @param lambdas how many invokedynamic sites produce the provider interface
     * @param audited whether the indy targets the audited lambda name
     * @param extraCall whether the forwarding lambda makes a call beyond forwarding
     */
    private static byte[] host(int lambdas, boolean audited, boolean extraCall) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, HOST, null, "java/lang/Object", null);

        MethodVisitor init = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        for (int i = 0; i < lambdas; i++) {
            Handle target = new Handle(Opcodes.H_INVOKESTATIC, HOST,
                audited ? "lambda$new$0" : "somethingElse", LAMBDA_DESC, false);
            init.visitInvokeDynamicInsn("getPathType", "()L" + PROVIDER + ";",
                new Handle(Opcodes.H_INVOKESTATIC,
                    "java/lang/invoke/LambdaMetafactory", "metafactory",
                    "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;"
                        + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;"
                        + "Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)"
                        + "Ljava/lang/invoke/CallSite;", false),
                Type.getMethodType(LAMBDA_DESC), target, Type.getMethodType(LAMBDA_DESC));
            init.visitInsn(Opcodes.POP);
        }
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(4, 1);
        init.visitEnd();

        MethodVisitor lambda = writer.visitMethod(
            Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "lambda$new$0", LAMBDA_DESC, null, null);
        lambda.visitCode();
        lambda.visitVarInsn(Opcodes.ALOAD, 0);
        lambda.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
            "net/minecraft/world/level/block/state/BlockState", "getBlock",
            "()Lnet/minecraft/world/level/block/Block;", false);
        if (extraCall) {
            lambda.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                "net/minecraft/world/level/BlockGetter", "getBlockState",
                "(Lnet/minecraft/core/BlockPos;)"
                    + "Lnet/minecraft/world/level/block/state/BlockState;", true);
        }
        lambda.visitInsn(Opcodes.ACONST_NULL);
        lambda.visitInsn(Opcodes.ARETURN);
        lambda.visitMaxs(4, 4);
        lambda.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    /** A decider whose body optionally loads one forbidden local slot. */
    private static byte[] decider(int loadedSlot) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, HOST, null, "java/lang/Object", null);
        MethodVisitor visitor = writer.visitMethod(
            Opcodes.ACC_PUBLIC, "getBlockPathType", DECIDER_DESC, null, null);
        visitor.visitCode();
        visitor.visitVarInsn(Opcodes.ALOAD, 1);        // the BlockState is always fine
        if (loadedSlot >= 0) visitor.visitVarInsn(Opcodes.ALOAD, loadedSlot);
        visitor.visitInsn(Opcodes.ACONST_NULL);
        visitor.visitInsn(Opcodes.ARETURN);
        visitor.visitMaxs(4, 5);
        visitor.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    /** A jar containing the host, optionally a second declaration and optionally a nested jar. */
    private static byte[] jar(boolean withOverride, boolean withNestedJar) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry(HOST + ".class"));
            zip.write(decider(-1));
            zip.closeEntry();
            if (withOverride) {
                zip.putNextEntry(new ZipEntry("vectorwing/farmersdelight/common/block/StoveBlock.class"));
                zip.write(subclassDecider());
                zip.closeEntry();
            }
            if (withNestedJar) {
                zip.putNextEntry(new ZipEntry("META-INF/jars/some-library-1.0.jar"));
                zip.write(new byte[] {1, 2, 3});
                zip.closeEntry();
            }
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
        return bytes.toByteArray();
    }

    private static byte[] subclassDecider() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC,
            "vectorwing/farmersdelight/common/block/StoveBlock", null, HOST, null);
        MethodVisitor visitor = writer.visitMethod(
            Opcodes.ACC_PUBLIC, "getBlockPathType", DECIDER_DESC, null, null);
        visitor.visitCode();
        visitor.visitVarInsn(Opcodes.ALOAD, 2);        // reads the world: the hazard
        visitor.visitInsn(Opcodes.ACONST_NULL);
        visitor.visitInsn(Opcodes.ARETURN);
        visitor.visitMaxs(4, 5);
        visitor.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }
}
