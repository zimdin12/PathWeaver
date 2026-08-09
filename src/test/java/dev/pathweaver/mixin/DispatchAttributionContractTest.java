package dev.pathweaver.mixin;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The dispatched request must carry the evaluator it was dispatched with.
 *
 * <p>A bytecode contract because a behavioural one is not available: the dispatch site is inside a
 * mixin, which no unit test can drive. And it is needed because the mutation survived everything
 * else — replacing {@code this.nodeEvaluator.getClass()} with {@code null} at the one production call
 * site left all of the breaker's tests, the pool's wiring test and the whole suite green, while every
 * worker failure in the shipped jar would be attributed to no family and the breaker could never
 * trip. Seventh instance in this project of a guard sitting one hop from the decision.
 */
class DispatchAttributionContractTest {

    @Test
    void theDispatchedRequestIsBuiltWithTheLiveEvaluatorClass() throws Exception {
        int[] constructorsSeen = {0};
        int[] precededByGetClass = {0};

        try (InputStream in = DispatchAttributionContractTest.class
                .getResourceAsStream("/dev/pathweaver/mixin/PathNavigationMixin.class")) {
            assertNotNull(in, "PathNavigationMixin.class not readable");
            new ClassReader(in.readAllBytes()).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                           String signature, String[] exceptions) {
                    return new MethodVisitor(Opcodes.ASM9) {
                        /** Whether the immediately preceding instruction was a getClass() call. */
                        private boolean lastWasGetClass;

                        @Override public void visitMethodInsn(int opcode, String owner, String method,
                                                              String desc, boolean isInterface) {
                            boolean isRequestCtor = opcode == Opcodes.INVOKESPECIAL
                                && owner.equals("dev/pathweaver/async/PathRequest")
                                && method.equals("<init>");
                            if (isRequestCtor) {
                                constructorsSeen[0]++;
                                if (lastWasGetClass) precededByGetClass[0]++;
                            }
                            lastWasGetClass = method.equals("getClass") && desc.equals("()Ljava/lang/Class;");
                        }

                        // Any other instruction between the two breaks the adjacency, which is the
                        // property being asserted: the last argument pushed is the live class, not a
                        // constant and not something read earlier and possibly stale.
                        @Override public void visitInsn(int opcode) { lastWasGetClass = false; }
                        @Override public void visitVarInsn(int opcode, int var) { lastWasGetClass = false; }
                        @Override public void visitFieldInsn(int o, String ow, String n, String d) {
                            lastWasGetClass = false;
                        }
                        @Override public void visitLdcInsn(Object value) { lastWasGetClass = false; }
                        @Override public void visitTypeInsn(int opcode, String type) {
                            lastWasGetClass = false;
                        }
                    };
                }
            }, ClassReader.SKIP_FRAMES);
        }

        assertTrue(constructorsSeen[0] > 0,
            "no PathRequest construction found in the dispatch mixin -- this contract is asserting "
                + "nothing, which is worse than failing");
        assertEquals(constructorsSeen[0], precededByGetClass[0],
            "every dispatched PathRequest must be built with the evaluator's live class as its last "
                + "argument. Passing null there costs nothing visible: the search still runs, the "
                + "result still installs, and every test still passes -- but a worker failure is then "
                + "attributed to no family, so the breaker counts nothing and can never trip.");
    }

    /**
     * A dispatch refused by a trip must be counted, not silently skipped.
     *
     * <p>Deleting the {@code markOutcome} call left the whole suite green. The consequence is not
     * cosmetic: a tripped family then shows up as {@code dispatched} simply ceasing to rise, with no
     * row anywhere saying why — which is precisely the vanishing setup failure 0.6.0 had to fix,
     * where the mod did nothing indefinitely while reporting itself healthy.
     */
    @Test
    void aDispatchRefusedByTheBreakerIsCounted() throws Exception {
        boolean[] asksTheBreaker = {false};
        boolean[] countsTheRefusal = {false};

        try (InputStream in = DispatchAttributionContractTest.class
                .getResourceAsStream("/dev/pathweaver/mixin/PathNavigationMixin.class")) {
            assertNotNull(in, "PathNavigationMixin.class not readable");
            new ClassReader(in.readAllBytes()).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public MethodVisitor visitMethod(int a, String n, String d, String sg,
                                                           String[] ex) {
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override public void visitMethodInsn(int opcode, String owner, String method,
                                                              String desc, boolean isInterface) {
                            if (owner.equals("dev/pathweaver/gate/SafetyGate")
                                    && method.equals("isDeniedByRuntimeFailure")) {
                                asksTheBreaker[0] = true;
                            }
                        }
                        @Override public void visitFieldInsn(int opcode, String owner, String name,
                                                             String desc) {
                            if (owner.equals("dev/pathweaver/async/RequestOutcome")
                                    && name.equals("BREAKER_OPEN")) {
                                countsTheRefusal[0] = true;
                            }
                        }
                    };
                }
            }, ClassReader.SKIP_FRAMES);
        }

        assertTrue(asksTheBreaker[0],
            "the dispatch gate must distinguish a runtime trip from a scan denial; without that, a "
                + "trip is reported as something no setting can fix");
        assertTrue(countsTheRefusal[0],
            "and it must record BREAKER_OPEN for the refusal, or a switched-off family is invisible "
                + "in /pathweaver status except as a number that stopped going up");
    }

}
