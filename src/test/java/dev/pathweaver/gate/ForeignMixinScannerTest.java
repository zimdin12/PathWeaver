package dev.pathweaver.gate;

import com.google.gson.JsonParser;
import net.minecraft.world.level.pathfinder.SwimNodeEvaluator;
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

    @Test void sharedPathfindingTargetsDenyEveryEligibleEvaluator() {
        for (String target : List.of(
            "net.minecraft.world.level.pathfinder.NodeEvaluator",
            "net.minecraft.world.level.pathfinder.PathfindingContext",
            "net.minecraft.world.entity.ai.navigation.PathNavigation",
            "net.minecraft.world.entity.ai.navigation.GroundPathNavigation",
            "net.minecraft.world.level.pathfinder.PathFinder"
        )) {
            assertEquals(Set.of(WalkNodeEvaluator.class, SwimNodeEvaluator.class),
                ForeignMixinScanner.denialsForTargets(List.of(target)), target);
        }
    }

    @Test void anyDiscoveryFailureFailsClosedForEveryEligibleEvaluator() {
        ForeignMixinScanner.ScanDecision decision = ForeignMixinScanner.decide(
            List.of(), List.of("broken nested jar: unreadable"));
        assertEquals(Set.of(WalkNodeEvaluator.class, SwimNodeEvaluator.class), decision.denied());
        assertEquals(0, decision.scanned());
        assertEquals(1, decision.failed());
    }

    @Test void activePluginTargetsAreEvaluatedLikeStaticMixins() {
        ForeignMixinScanner.ActiveConfig pluginConfig = new ForeignMixinScanner.ActiveConfig(
            "foreign", "1.0", "foreign.mixins.json",
            Set.of(new ForeignMixinScanner.TargetClaim("foreign.PluginMixin",
                "net.minecraft.world.level.pathfinder.PathFinder")), true);
        ForeignMixinScanner.ScanDecision decision = ForeignMixinScanner.decide(
            List.of(pluginConfig), List.of());
        assertEquals(Set.of(WalkNodeEvaluator.class, SwimNodeEvaluator.class), decision.denied());
        assertEquals(1, decision.scanned());
        assertEquals(0, decision.failed());
    }

    @Test void fabricMetadataDiscoveryHonorsServerEnvironmentAndObjectForm() {
        var metadata = JsonParser.parseString("""
            {"mixins":[
              "common.mixins.json",
              {"config":"server.mixins.json","environment":"server"},
              {"config":"client.mixins.json","environment":"client"}
            ]}
            """).getAsJsonObject();
        assertEquals(List.of("common.mixins.json", "server.mixins.json"),
            ForeignMixinScanner.readServerMixinConfigNames(metadata));
    }

    @Test void malformedFabricMixinMetadataFailsInsteadOfDisappearing() {
        var metadata = JsonParser.parseString("{\"mixins\":[42]}").getAsJsonObject();
        assertThrows(IllegalArgumentException.class,
            () -> ForeignMixinScanner.readServerMixinConfigNames(metadata));
    }

    @Test void oldBroadTrustRulesAreGone() {
        assertFalse(ForeignMixinScanner.isAuditedExemption(
            "fabric-anything", "1", "x.mixins.json", "x.Mixin",
            "net.minecraft.world.level.pathfinder.PathFinder"));
        assertFalse(ForeignMixinScanner.isAuditedExemption(
            "diagonal-anything", "1", "x.mixins.json", "x.Mixin",
            "net.minecraft.world.level.pathfinder.PathFinder"));
        assertFalse(ForeignMixinScanner.isAuditedExemption(
            "lithium", "unknown", "x.mixins.json", "x.Mixin",
            "net.minecraft.world.level.pathfinder.PathFinder"));
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
