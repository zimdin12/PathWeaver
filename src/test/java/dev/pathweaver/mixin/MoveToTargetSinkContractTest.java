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

    /** Handler method name -> the annotation descriptors on it. */
    private static Map<String, List<String>> handlerAnnotations() throws Exception {
        Map<String, List<String>> found = new LinkedHashMap<>();
        try (InputStream in = MoveToTargetSinkContractTest.class
                .getResourceAsStream("/dev/pathweaver/mixin/MoveToTargetSinkMixin.class")) {
            assertNotNull(in, "MoveToTargetSinkMixin.class not readable");
            new ClassReader(in.readAllBytes()).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public MethodVisitor visitMethod(int access, String name, String desc,
                                                           String sig, String[] ex) {
                    List<String> annotations =
                        found.computeIfAbsent(name, key -> new ArrayList<>());
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override public org.objectweb.asm.AnnotationVisitor visitAnnotation(
                                String annotationDesc, boolean visible) {
                            annotations.add(annotationDesc);
                            return null;
                        }
                    };
                }
            }, 0);
        }
        return found;
    }

    @Test void bothCallSitesAreStillHooked() throws Exception {
        Map<String, List<String>> handlers = handlerAnnotations();

        long injects = handlers.values().stream()
            .filter(a -> a.contains(INJECT)).count();
        long wraps = handlers.values().stream()
            .filter(a -> a.contains(WRAP_OPERATION)).count();

        // Three @Inject: the start-check decision, its RETURN release, and the tick() re-path route.
        assertTrue(injects >= 3,
            "expected three @Inject handlers (start-check decision, its RETURN release, and the "
                + "tick() re-path route) but found " + injects + ". Deleting the tryComputePath hook "
                + "removes the offload from the route that dominates mobs following a moving entity, "
                + "and no game test can see it: they all pin a stationary destination.");
        assertTrue(wraps >= 1,
            "the @WrapOperation that hands a landed path back to vanilla's own createPath call site "
                + "is gone; without it the collected path is never delivered");
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
                            if (mName.equals("pathweaver$decideDefers")) order.add("decide");
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
