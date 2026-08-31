package dev.pathweaver.mixin;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Locks the two structural properties of the brain-sink hook that its correctness rests on.
 *
 * <p>Both were previously pinned by comments alone, and both were regressed once. A review round
 * found that the {@code tryComputePath} hook had been deleted, silently removing the offload from the
 * {@code tick()} re-path route -- the one that dominates anything following a moving entity -- and
 * every game test stayed green, because they all pin a stationary destination so {@code tick()}'s
 * {@code distSqr > 4.0} guard never fires.
 *
 * <p>Two game-test attempts to cover that behaviourally both failed to be attributable: a pending
 * brain-sink slot observed while a mob is walking can belong to a dispatch the START check made for a
 * destination the mob has since drifted away from, so the mutation survived both. The route's
 * PRESENCE is a structural fact, and a structural test is the honest instrument for it. What this
 * does NOT do is prove the tick route behaves correctly once taken; that remains uncovered and is
 * said so here rather than implied by a green run.
 */
class MoveToTargetSinkContractTest {

    private static final String INJECT = "Lorg/spongepowered/asm/mixin/injection/Inject;";
    private static final String WRAP_OPERATION =
        "Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;";

    /** Annotation descriptor -> the `method` values declared on it, across the whole mixin. */
    private static Map<String, List<String>> injectedTargets() throws Exception {
        Map<String, List<String>> byAnnotation = new LinkedHashMap<>();
        try (InputStream in = MoveToTargetSinkContractTest.class
                .getResourceAsStream("/dev/pathweaver/mixin/MoveToTargetSinkMixin.class")) {
            assertNotNull(in, "MoveToTargetSinkMixin.class not readable");
            new ClassReader(in.readAllBytes()).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public MethodVisitor visitMethod(int access, String name, String desc,
                                                           String sig, String[] ex) {
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override public org.objectweb.asm.AnnotationVisitor visitAnnotation(
                                String annotationDesc, boolean visible) {
                            List<String> targets = byAnnotation
                                .computeIfAbsent(annotationDesc, k -> new ArrayList<>());
                            return new org.objectweb.asm.AnnotationVisitor(Opcodes.ASM9) {
                                @Override public org.objectweb.asm.AnnotationVisitor visitArray(
                                        String arrayName) {
                                    if (!"method".equals(arrayName)) return null;
                                    return new org.objectweb.asm.AnnotationVisitor(Opcodes.ASM9) {
                                        @Override public void visit(String n, Object value) {
                                            targets.add(String.valueOf(value));
                                        }
                                    };
                                }
                            };
                        }
                    };
                }
            }, 0);
        }
        return byAnnotation;
    }

    private static final String START_CHECK =
        "checkExtraStartConditions(Lnet/minecraft/server/level/ServerLevel;"
            + "Lnet/minecraft/world/entity/Mob;)Z";
    private static final String TRY_COMPUTE_PATH =
        "tryComputePath(Lnet/minecraft/world/entity/Mob;"
            + "Lnet/minecraft/world/entity/ai/memory/WalkTarget;J)Z";

    /**
     * Both seams, pinned by the METHOD THEY TARGET rather than by how many annotations exist.
     *
     * <p>The first version of this counted handlers -- {@code injects >= 3}, {@code wraps >= 1} --
     * and two reviewers independently pointed out that it would stay green if the tryComputePath
     * hook were deleted and any other {@code @Inject} added. Counting is not pinning. It also let the
     * descriptor-less {@code method = "tryComputePath"} through, which would have bound to BOTH
     * overloads if Mojang ever added one, while still satisfying {@code require = 1}.
     */
    @Test void bothCallSitesAreStillHookedByExactDescriptor() throws Exception {
        Map<String, List<String>> targets = injectedTargets();

        List<String> injected = targets.getOrDefault(INJECT, List.of());
        List<String> wrapped = targets.getOrDefault(WRAP_OPERATION, List.of());

        assertTrue(injected.contains(START_CHECK),
            "the start-check deferral is gone or retargeted. It must be on "
                + START_CHECK + "; found " + injected);
        assertTrue(injected.contains(TRY_COMPUTE_PATH),
            "the tick() re-path hook is gone or retargeted. Deleting it removes the offload from the "
                + "route that dominates mobs following a moving entity, and no game test can see it: "
                + "they all pin a stationary destination. Found " + injected);
        assertTrue(wrapped.contains(TRY_COMPUTE_PATH),
            "the @WrapOperation that hands a landed path back to vanilla's own createPath call site "
                + "is gone; without it a collected path is never delivered. Found " + wrapped);
        assertEquals(2, injected.stream().filter(START_CHECK::equals).count(),
            "the start check needs exactly two @Injects -- the decision at HEAD and the claim "
                + "release at RETURN; found " + injected);
    }

    /**
     * Vanilla's two cheap early-outs must be consulted before anything is taken or dispatched.
     *
     * <p>The hook sits at the HEAD of {@code checkExtraStartConditions}, above both
     * {@code remainingCooldown} (offsets 0-18) and {@code reachedTarget} (39-50). Skipping them made
     * the mod dispatch a search for a mob vanilla had deliberately stopped pathing, and for a mob
     * already standing on its destination. Checking them AFTER {@code takeBrainSinkPath} would be
     * just as wrong in a subtler way: taking removes the slot, and if vanilla then returns at a guard
     * the wrap never runs and the answer is destroyed, which turns one wasted search into a loop.
     */
    @Test void vanillaGuardsAreConsultedBeforeTheSlotIsTouched() throws Exception {
        List<String> order = new ArrayList<>();
        try (InputStream in = MoveToTargetSinkContractTest.class
                .getResourceAsStream("/dev/pathweaver/mixin/MoveToTargetSinkMixin.class")) {
            assertNotNull(in);
            new ClassReader(in.readAllBytes()).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public MethodVisitor visitMethod(int access, String name, String desc,
                                                           String sig, String[] ex) {
                    if (!name.contains("deferBeforeVanillaCanForgetTheTarget")) return null;
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override public void visitFieldInsn(int op, String owner, String fName,
                                                             String fDesc) {
                            if (fName.equals("remainingCooldown")) order.add("remainingCooldown");
                        }
                        @Override public void visitMethodInsn(int op, String owner, String mName,
                                                              String mDesc, boolean itf) {
                            if (mName.equals("reachedTarget")) order.add("reachedTarget");
                            // Either name counts. The handler used to call the decision
                            // directly; it now enters through claimAndDecide, which holds the
                            // claim across it. What this test pins is the BOUNDARY -- vanilla's
                            // guards run before anything touches the parked slot -- not which
                            // method the boundary happens to be spelled as today.
                            if (mName.equals("pathweaver$decideDefers")
                                    || mName.equals("pathweaver$claimAndDecide")) {
                                order.add("decide");
                            }
                        }
                    };
                }
            }, 0);
        }

        if (order.isEmpty()) fail("the start-check handler was not found, so this asserts nothing");
        assertTrue(order.contains("remainingCooldown"),
            "the stuck-mob cooldown guard is gone; the mod would pathfind for a mob vanilla has "
                + "deliberately stopped pathing, and the cancel skips the decrement so the throttle "
                + "would take about twice as long to expire");
        assertTrue(order.contains("reachedTarget"),
            "the arrival guard is gone; the mod would dispatch a search for a block the mob is "
                + "already standing on and withhold vanilla's arrival erase");
        assertTrue(order.indexOf("remainingCooldown") < order.indexOf("decide")
                && order.indexOf("reachedTarget") < order.indexOf("decide"),
            "both vanilla guards must be consulted BEFORE the decision touches the parked slot; "
                + "order was " + order);
    }
}
