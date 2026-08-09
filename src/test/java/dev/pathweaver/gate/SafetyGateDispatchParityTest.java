package dev.pathweaver.gate;

import net.minecraft.world.level.pathfinder.SwimNodeEvaluator;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code canDispatch} is a RECONSTRUCTION of what dispatch does, and nothing pinned it to the original.
 *
 * <p>The mixin does not call {@code canDispatch}. It performs three separate steps — {@code isAllowed},
 * then {@code requiresEmptyLandRegistry}, then the latch — and {@code canDispatch} exists so the banner
 * and {@code /pathweaver status} can answer the same question. A reviewer reduced the production
 * {@code canDispatch} to {@code return isAllowed(c);} and all 289 tests stayed green, which would have
 * restored the release's headline bug: "ACTIVE: all 6 movement families" while five of six are refused
 * every tick.
 *
 * <p>The reason the old test missed it is that it computed its expectation by calling the very method
 * under test. This one recomputes the dispatch sequence independently and asserts the reconstruction
 * matches it, over the whole input space.
 */
class SafetyGateDispatchParityTest {

    /** What {@code PathNavigationMixin} actually evaluates, spelled out rather than delegated. */
    private static boolean whatDispatchDoes(Class<?> evaluator, boolean allowed,
                                            boolean bypassesScan, boolean latchAllows) {
        if (!allowed) return false;
        boolean gatedOnRegistry = SafetyGate.requiresEmptyLandRegistry(evaluator, bypassesScan);
        return !gatedOnRegistry || latchAllows;
    }

    @Test
    void theReportedPredicateMatchesTheDispatchSequenceOverTheWholeInputSpace() throws Exception {
        Class<?>[] families = {
            WalkNodeEvaluator.class,
            SwimNodeEvaluator.class,
            net.minecraft.world.level.pathfinder.FlyNodeEvaluator.class,
            net.minecraft.world.level.pathfinder.AmphibiousNodeEvaluator.class,
            Class.forName("net.minecraft.world.entity.animal.frog.Frog$FrogNodeEvaluator"),
            Class.forName("net.minecraft.world.entity.monster.creaking.Creaking$HomeNodeEvaluator"),
        };
        for (Class<?> family : families) {
            for (boolean allowed : new boolean[] {false, true}) {
                for (boolean bypass : new boolean[] {false, true}) {
                    for (boolean latch : new boolean[] {false, true}) {
                        assertEquals(
                            whatDispatchDoes(family, allowed, bypass, latch),
                            SafetyGate.canDispatch(family, allowed, bypass, latch),
                            () -> family.getSimpleName() + " allowed=" + allowed + " bypass=" + bypass
                                + " latch=" + latch + ": what the banner reports must equal what "
                                + "dispatch does, or one of them is lying to the operator");
                    }
                }
            }
        }
    }

    /**
     * The reconstruction must not collapse to its first term.
     *
     * <p>Stated separately because that is the exact mutation that survived: {@code canDispatch}
     * reduced to {@code isAllowed} passes any test whose expectation is also computed from
     * {@code canDispatch}.
     */
    @Test
    void anAllowedLandFamilyIsStillRefusedWhileTheRegistryIsUnverified() {
        assertEquals(false, SafetyGate.canDispatch(WalkNodeEvaluator.class, true, false, false),
            "allowed is not sufficient: the land registry must still be able to refuse");
        assertEquals(true, SafetyGate.canDispatch(SwimNodeEvaluator.class, true, false, false),
            "and it must not refuse a family that never reads that registry");
    }

    /**
     * The banner must CALL the shared predicate, not merely agree with it in a test.
     *
     * <p>Reverting {@code PathWeaverRuntime}'s family count from {@code canDispatch} to
     * {@code isAllowed} survived all 302 tests — restoring the release's headline bug, where the
     * banner announces "all 6 movement families" while five are refused every tick. The parity test
     * above pins what {@code canDispatch} computes; nothing pinned that the banner uses it, and
     * {@code ScanSummaryTest} re-implements the counting rather than invoking the production path.
     * That is the same "one hop from the call site" failure this project has now hit four times, so
     * this walks the shipped bytecode instead.
     */
    @Test
    void theStartupBannerCountsFamiliesThroughCanDispatch() throws Exception {
        boolean[] callsCanDispatch = {false};
        boolean[] callsIsAllowed = {false};
        try (java.io.InputStream in = SafetyGateDispatchParityTest.class
                .getResourceAsStream("/dev/pathweaver/PathWeaverRuntime.class")) {
            org.junit.jupiter.api.Assertions.assertNotNull(in, "PathWeaverRuntime.class not readable");
            new org.objectweb.asm.ClassReader(in.readAllBytes()).accept(
                new org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9) {
                    @Override public org.objectweb.asm.MethodVisitor visitMethod(
                            int a, String name, String d, String sg, String[] ex) {
                        if (!name.toLowerCase().contains("report")) return null;
                        return new org.objectweb.asm.MethodVisitor(org.objectweb.asm.Opcodes.ASM9) {
                            @Override public void visitMethodInsn(int op, String owner, String m,
                                                                  String md, boolean itf) {
                                if (!owner.equals("dev/pathweaver/gate/SafetyGate")) return;
                                if (m.equals("canDispatch")) callsCanDispatch[0] = true;
                                if (m.equals("isAllowed")) callsIsAllowed[0] = true;
                            }
                        };
                    }
                }, org.objectweb.asm.ClassReader.SKIP_FRAMES);
        }
        assertTrue(callsCanDispatch[0],
            "the startup banner must count families with SafetyGate.canDispatch -- counting with "
                + "isAllowed omits the land-registry latch and announces families that dispatch "
                + "refuses on every tick");
        assertFalse(callsIsAllowed[0],
            "the banner must not also count with isAllowed; two predicates is how the banner and "
                + "dispatch drifted apart in the first place");
    }

    /**
     * The no-argument wrappers every production site calls, pinned by what their bodies DO.
     *
     * <p>Three separate wrappers had no test at all, and each is the same shape the project has now
     * hit five times: a state-injecting form gets tested and the wrapper the production code actually
     * calls does not. {@code canDispatch(Class)} reduced to {@code return isAllowed(c)} survived 308
     * tests and fully restored this release's headline bug. So did calling the four-argument form with
     * hard-coded {@code true, true}, which still "calls canDispatch" and satisfies a check that only
     * looks at the name.
     *
     * <p>A value-parity test cannot catch these: in a unit environment the latch is closed and both
     * sides agree by accident. So this asserts the instructions.
     */
    @Test
    void theNoArgumentWrappersReadTheStateTheyClaimTo() throws Exception {
        var canDispatch = bodyCalls("dev/pathweaver/gate/SafetyGate", "canDispatch",
            "(Ljava/lang/Class;)Z");
        assertTrue(canDispatch.contains("dev/pathweaver/gate/SafetyGate.isAllowed"),
            "canDispatch(Class) must consult isAllowed: " + canDispatch);
        assertTrue(canDispatch.contains(
                "dev/pathweaver/gate/ActiveCompatibilityPolicy.bypassesScan"),
            "canDispatch(Class) must read the frozen tier: " + canDispatch);
        assertTrue(canDispatch.contains(
                "dev/pathweaver/gate/FabricLandPathRegistryLatch.allowsWalkDispatch"),
            "canDispatch(Class) must consult the land-registry latch -- omitting it announces "
                + "families that dispatch refuses on every tick: " + canDispatch);
        assertFalse(pushesConstantInto("dev/pathweaver/gate/SafetyGate", "canDispatch",
                "(Ljava/lang/Class;)Z"),
            "canDispatch(Class) must not hard-code its state arguments -- passing true collapses the "
                + "predicate to `allowed`, which is the bug it exists to prevent");

        var scanFailed = bodyCalls("dev/pathweaver/gate/ForeignMixinScanner", "scanFailed", "()Z");
        assertTrue(scanFailed.stream().anyMatch(c -> c.endsWith(".lastScanReport")),
            "scanFailed() must read the live scan report: " + scanFailed);
        assertTrue(scanFailed.stream().anyMatch(c -> c.endsWith(".failed")
                || c.endsWith(".decision") || c.contains("scanFailed")),
            "scanFailed() must derive from that report's failure count: " + scanFailed);
    }

    /** Every method this method body invokes, as {@code owner.name}. */
    private static java.util.Set<String> bodyCalls(String owner, String method, String desc)
            throws Exception {
        java.util.Set<String> calls = new java.util.LinkedHashSet<>();
        new org.objectweb.asm.ClassReader(bytes(owner)).accept(
            new org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9) {
                @Override public org.objectweb.asm.MethodVisitor visitMethod(
                        int a, String n, String d, String sg, String[] ex) {
                    if (!n.equals(method) || !d.equals(desc)) return null;
                    return new org.objectweb.asm.MethodVisitor(org.objectweb.asm.Opcodes.ASM9) {
                        @Override public void visitMethodInsn(int op, String o, String m, String md,
                                                              boolean itf) {
                            calls.add(o + "." + m);
                        }
                    };
                }
            }, org.objectweb.asm.ClassReader.SKIP_FRAMES);
        assertFalse(calls.isEmpty(), "no body found for " + owner + "." + method + desc);
        return calls;
    }

    /** True if the body pushes an int constant, i.e. hard-codes an argument it should read. */
    private static boolean pushesConstantInto(String owner, String method, String desc)
            throws Exception {
        boolean[] pushed = {false};
        new org.objectweb.asm.ClassReader(bytes(owner)).accept(
            new org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9) {
                @Override public org.objectweb.asm.MethodVisitor visitMethod(
                        int a, String n, String d, String sg, String[] ex) {
                    if (!n.equals(method) || !d.equals(desc)) return null;
                    return new org.objectweb.asm.MethodVisitor(org.objectweb.asm.Opcodes.ASM9) {
                        @Override public void visitInsn(int op) {
                            if (op == org.objectweb.asm.Opcodes.ICONST_1
                                    || op == org.objectweb.asm.Opcodes.ICONST_0) pushed[0] = true;
                        }
                    };
                }
            }, org.objectweb.asm.ClassReader.SKIP_FRAMES);
        return pushed[0];
    }

    private static byte[] bytes(String internalName) throws Exception {
        try (java.io.InputStream in = SafetyGateDispatchParityTest.class
                .getResourceAsStream("/" + internalName + ".class")) {
            org.junit.jupiter.api.Assertions.assertNotNull(in, internalName + " not readable");
            return in.readAllBytes();
        }
    }
}
