package dev.pathweaver.mixin;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A movement entry point that a navigation subclass OVERRIDES is invisible to the base mixin.
 *
 * <p>Third instance of one mistake, so it gets a contract rather than another fix. A mixin transforms
 * its target class only: {@code PathNavigationMixin} injects at
 * {@code PathNavigation.moveTo(Entity, double)} to mark that a genuine movement request is starting,
 * and {@code WallClimberNavigation} overrides that method without calling {@code super}. The inject
 * never ran, so every spider chasing a player pathed synchronously — while {@code /pathweaver mobs}
 * counted spiders as eligible, i.e. the mod reported coverage it did not have.
 *
 * <p>The same shape produced the 0.5.1 blocker ({@code AmphibiousNodeEvaluator} overriding
 * {@code getNeighbors}) and the 0.5.2 one. This test derives the overriding classes from Minecraft's
 * own bytecode rather than a hand-written list, so a subclass added by a future version fails here
 * instead of quietly opting out of the mod.
 */
class NavigationOverrideCoverageTest {

    /** The methods {@code PathNavigationMixin} injects into to mark a real movement request. */
    private static final Set<String> MOVEMENT_ENTRY_POINTS = Set.of(
        "moveTo(DDDD)Z",
        "moveTo(DDDID)Z",
        "moveTo(Lnet/minecraft/world/entity/Entity;D)Z");

    private static final List<String> NAVIGATION_SUBCLASSES = List.of(
        "net/minecraft/world/entity/ai/navigation/GroundPathNavigation",
        "net/minecraft/world/entity/ai/navigation/WallClimberNavigation",
        "net/minecraft/world/entity/ai/navigation/FlyingPathNavigation",
        "net/minecraft/world/entity/ai/navigation/WaterBoundPathNavigation",
        "net/minecraft/world/entity/ai/navigation/AmphibiousPathNavigation");

    @Test
    void everyNavigationSubclassThatOverridesAMovementEntryPointHasItsOwnMixin() throws Exception {
        Set<String> mixedIn = shippedMixinTargets();
        assertFalse(mixedIn.isEmpty(), "no mixin targets resolved — this test would prove nothing");

        StringBuilder unguarded = new StringBuilder();
        for (String navigation : NAVIGATION_SUBCLASSES) {
            for (String overridden : declaredMovementEntryPoints(navigation)) {
                if (!mixedIn.contains(navigation)) {
                    unguarded.append("\n  ").append(navigation).append(" overrides ").append(overridden)
                        .append(" and no mixin targets it");
                }
            }
        }
        assertTrue(unguarded.isEmpty(),
            "a navigation subclass overrides a movement entry point without a mixin of its own, so "
                + "the base inject never runs for it and those mobs silently path on the server "
                + "thread while the mod reports them eligible:" + unguarded);
    }

    @Test
    void theWallClimberOverrideIsRealAndIsCovered() throws Exception {
        // Non-vacuity: if a future version stops overriding it, the loop above passes trivially and
        // this test says so instead of leaving a green suite that checks nothing.
        assertTrue(declaredMovementEntryPoints(
                "net/minecraft/world/entity/ai/navigation/WallClimberNavigation")
                .contains("moveTo(Lnet/minecraft/world/entity/Entity;D)Z"),
            "WallClimberNavigation is expected to override moveTo(Entity, double) — that override is "
                + "why spiders were never dispatched");
        assertTrue(shippedMixinTargets()
                .contains("net/minecraft/world/entity/ai/navigation/WallClimberNavigation"),
            "and the shipped config must carry a mixin for it");
    }

    /** Movement entry points this class DECLARES itself, from real bytecode. */
    private static Set<String> declaredMovementEntryPoints(String internalName) throws IOException {
        Set<String> found = new LinkedHashSet<>();
        new ClassReader(classBytes(internalName)).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                if (MOVEMENT_ENTRY_POINTS.contains(name + descriptor)) found.add(name + descriptor);
                return null;
            }
        }, ClassReader.SKIP_CODE);
        return found;
    }

    /** Internal names of the classes the shipped mixin config targets. */
    private static Set<String> shippedMixinTargets() throws Exception {
        Set<String> targets = new LinkedHashSet<>();
        try (InputStream in = NavigationOverrideCoverageTest.class
                .getResourceAsStream("/pathweaver.mixins.json")) {
            if (in == null) throw new IOException("pathweaver.mixins.json is not on the test classpath");
            JsonObject root = JsonParser.parseString(
                new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
            for (var element : root.getAsJsonArray("mixins")) {
                Class<?> mixin = Class.forName("dev.pathweaver.mixin." + element.getAsString());
                new ClassReader(classBytes(mixin.getName().replace('.', '/')))
                    .accept(new ClassVisitor(Opcodes.ASM9) {
                        @Override public org.objectweb.asm.AnnotationVisitor visitAnnotation(
                                String desc, boolean visible) {
                            if (!desc.equals("Lorg/spongepowered/asm/mixin/Mixin;")) return null;
                            return new org.objectweb.asm.AnnotationVisitor(Opcodes.ASM9) {
                                @Override public org.objectweb.asm.AnnotationVisitor visitArray(String k) {
                                    if (!k.equals("value")) return null;
                                    return new org.objectweb.asm.AnnotationVisitor(Opcodes.ASM9) {
                                        @Override public void visit(String ignored, Object value) {
                                            targets.add(((org.objectweb.asm.Type) value)
                                                .getInternalName());
                                        }
                                    };
                                }
                            };
                        }
                    }, ClassReader.SKIP_CODE);
            }
        }
        return targets;
    }

    private static byte[] classBytes(String internalName) throws IOException {
        try (InputStream in = NavigationOverrideCoverageTest.class
                .getResourceAsStream("/" + internalName + ".class")) {
            if (in == null) throw new IOException("missing class resource " + internalName);
            return in.readAllBytes();
        }
    }
}
