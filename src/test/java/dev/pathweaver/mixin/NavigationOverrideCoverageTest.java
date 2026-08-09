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

    /**
     * Every {@code PathNavigation} subclass Minecraft ships, discovered rather than listed.
     *
     * <p>This was a hand-written list of five while the jar contains ten — {@code FrogPathNavigation},
     * {@code BabyFlyingPathNavigation}, {@code TurtlePathNavigation}, {@code StriderPathNavigation}
     * and {@code CreakingPathNavigation} were missing. None of them overrides a movement entry point
     * today, so there was no live bug, but the javadoc claimed derivation while doing enumeration:
     * the exact "the list was one entry short" failure this file exists to end, inside the file that
     * says so.
     */
    private static List<String> navigationSubclasses() throws Exception {
        java.util.List<String> found = new java.util.ArrayList<>();
        java.net.URL url = Class.forName("net.minecraft.world.entity.ai.navigation.PathNavigation")
            .getProtectionDomain().getCodeSource().getLocation();
        java.nio.file.Path jar = java.nio.file.Paths.get(url.toURI());
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(jar.toFile())) {
            for (var e : java.util.Collections.list(zip.entries())) {
                String n = e.getName();
                if (!n.endsWith(".class") || !n.startsWith("net/minecraft/")) continue;
                String internal = n.substring(0, n.length() - 6);
                if (internal.equals("net/minecraft/world/entity/ai/navigation/PathNavigation")) continue;
                try (InputStream in = zip.getInputStream(e)) {
                    if (extendsPathNavigation(in.readAllBytes(), zip)) found.add(internal);
                } catch (IOException | RuntimeException ignored) {
                    // Unreadable entries are not silently dropped -- they would hide a subclass.
                    throw new IllegalStateException("could not read " + internal);
                }
            }
        }
        return List.copyOf(found);
    }

    /** Walk the superclass chain inside the jar, so indirect subclasses are found too. */
    private static boolean extendsPathNavigation(byte[] bytes, java.util.zip.ZipFile zip)
            throws IOException {
        String[] superName = new String[1];
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public void visit(int v, int a, String name, String sig, String sup,
                                        String[] itf) {
                superName[0] = sup;
            }
        }, ClassReader.SKIP_CODE);
        int guard = 0;
        String sup = superName[0];
        while (sup != null && sup.startsWith("net/minecraft/") && guard++ < 16) {
            if (sup.equals("net/minecraft/world/entity/ai/navigation/PathNavigation")) return true;
            var entry = zip.getEntry(sup + ".class");
            if (entry == null) return false;
            String[] next = new String[1];
            try (InputStream in = zip.getInputStream(entry)) {
                new ClassReader(in.readAllBytes()).accept(new ClassVisitor(Opcodes.ASM9) {
                    @Override public void visit(int v, int a, String name, String sig, String s2,
                                                String[] itf) {
                        next[0] = s2;
                    }
                }, ClassReader.SKIP_CODE);
            }
            sup = next[0];
        }
        return false;
    }

    @Test
    void everyNavigationSubclassThatOverridesAMovementEntryPointHasItsOwnMixin() throws Exception {
        Set<String> mixedIn = shippedMixinTargets();
        assertFalse(mixedIn.isEmpty(), "no mixin targets resolved — this test would prove nothing");

        StringBuilder unguarded = new StringBuilder();
        List<String> subclasses = navigationSubclasses();
        assertTrue(subclasses.size() >= 5,
            "discovery found only " + subclasses.size() + " navigation subclasses; a walk "
                + "that finds too few would pass this contract by looking at nothing");
        for (String navigation : subclasses) {
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

    /**
     * A mixin that targets the class but injects nothing is not coverage.
     *
     * <p>Four separate mutations reintroduced the spider bug and survived the whole unit suite:
     * emptying the mixin body, deleting the {@code @WrapOperation}, deleting the {@code @Inject},
     * and making the depth marker a no-op. {@code shippedMixinTargets()} reads only the
     * {@code @Mixin} target, so an empty class registered in the config looked identical to a
     * working one — the same "asserted one hop from the call site" failure this release keeps
     * hitting.
     *
     * <p>All three injections are required, and each was missed in turn: the marker (speed), the
     * request depth (the {@code @WrapOperation} around the override's own {@code createPath}), and
     * the accepted-dispatch result (without which an accepted search reports FAILURE to
     * {@code MeleeAttackGoal}, which answers it with a fifteen-tick chase stall).
     */
    @Test
    void theWallClimberMixinCarriesAllThreeInjectionsItNeeds() throws Exception {
        Set<String> injected = injectorTargetsOf("dev.pathweaver.mixin.WallClimberNavigationMixin");
        assertTrue(injected.stream().anyMatch(i ->
                i.startsWith("Inject:moveTo(Lnet/minecraft/world/entity/Entity;D)Z")),
            "the movement marker and the deferred result are @Injects on the override: " + injected);
        assertTrue(injected.stream().anyMatch(i ->
                i.startsWith("WrapOperation:moveTo(Lnet/minecraft/world/entity/Entity;D)Z")),
            "the request depth needs a @WrapOperation around the override's own createPath call, "
                + "without which the inner call still reads as a query and stays synchronous: "
                + injected);
        assertTrue(injected.stream().filter(i -> i.startsWith("Inject:")).count() >= 2,
            "two @Injects are required -- the movement marker at HEAD and the accepted-dispatch "
                + "result at RETURN: " + injected);
    }

    /** {@code Inject:moveTo(...)Z} style entries for every injector the mixin declares. */
    private static Set<String> injectorTargetsOf(String mixinClass) throws Exception {
        Set<String> out = new LinkedHashSet<>();
        new ClassReader(classBytes(mixinClass.replace('.', '/'))).accept(
            new ClassVisitor(Opcodes.ASM9) {
                @Override public MethodVisitor visitMethod(int a, String handler, String d,
                                                           String sg, String[] ex) {
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override public org.objectweb.asm.AnnotationVisitor visitAnnotation(
                                String desc, boolean visible) {
                            String simple = desc.substring(desc.lastIndexOf('/') + 1)
                                .replace(";", "");
                            return new org.objectweb.asm.AnnotationVisitor(Opcodes.ASM9) {
                                @Override public org.objectweb.asm.AnnotationVisitor visitArray(
                                        String key) {
                                    if (!"method".equals(key)) return null;
                                    return new org.objectweb.asm.AnnotationVisitor(Opcodes.ASM9) {
                                        @Override public void visit(String ig, Object v) {
                                            out.add(simple + ":" + v + "@" + handler);
                                        }
                                    };
                                }
                                @Override public void visit(String key, Object v) {
                                    if ("method".equals(key)) out.add(simple + ":" + v + "@" + handler);
                                }
                            };
                        }
                    };
                }
            }, ClassReader.SKIP_CODE);
        return out;
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
