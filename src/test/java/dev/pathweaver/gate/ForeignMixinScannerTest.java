package dev.pathweaver.gate;

import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ForeignMixinScannerTest {

    // ---- pure helper ----
    @Test void detectsMixinIntoAllowlistedClass() {
        Set<Class<?>> hit = ForeignMixinScanner.targetsTouchingAllowlist(
            List.of("net.minecraft.world.level.pathfinder.WalkNodeEvaluator"));
        assertTrue(hit.contains(WalkNodeEvaluator.class));
    }

    @Test void ignoresUnrelatedTargets() {
        Set<Class<?>> hit = ForeignMixinScanner.targetsTouchingAllowlist(
            List.of("net.minecraft.world.entity.Mob"));
        assertTrue(hit.isEmpty());
    }

    // ---- ASM annotation reader (synthesize a @Mixin-annotated class, read it back) ----
    @Test void readsStringTargetsForm() {
        byte[] bytes = synthMixinClass("StringTargetMixin", false,
            "net.minecraft.world.level.pathfinder.WalkNodeEvaluator");
        List<String> t = ForeignMixinScanner.readMixinAnnotationTargets(bytes);
        assertTrue(t.contains("net.minecraft.world.level.pathfinder.WalkNodeEvaluator"), t.toString());
    }

    @Test void readsClassValueForm() {
        byte[] bytes = synthMixinClass("ClassValueMixin", true,
            "net.minecraft.world.level.pathfinder.FlyNodeEvaluator");
        List<String> t = ForeignMixinScanner.readMixinAnnotationTargets(bytes);
        assertTrue(t.contains("net.minecraft.world.level.pathfinder.FlyNodeEvaluator"), t.toString());
    }

    @Test void endToEndTargetsMapToAllowlist() {
        byte[] bytes = synthMixinClass("E2EMixin", true,
            "net.minecraft.world.level.pathfinder.WalkNodeEvaluator");
        Set<Class<?>> hit = ForeignMixinScanner.targetsTouchingAllowlist(
            ForeignMixinScanner.readMixinAnnotationTargets(bytes));
        assertEquals(Set.of(WalkNodeEvaluator.class), hit);
    }

    /**
     * Build a minimal class carrying an invisible @Mixin annotation.
     * @param classValue true => @Mixin(value = {Target.class}); false => @Mixin(targets = {"a.b.C"})
     */
    private static byte[] synthMixinClass(String name, boolean classValue, String target) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "dev/pathweaver/gen/" + name, null, "java/lang/Object", null);
        AnnotationVisitor av = cw.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false);
        if (classValue) {
            AnnotationVisitor arr = av.visitArray("value");
            arr.visit(null, Type.getObjectType(target.replace('.', '/')));
            arr.visitEnd();
        } else {
            AnnotationVisitor arr = av.visitArray("targets");
            arr.visit(null, target);
            arr.visitEnd();
        }
        av.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
