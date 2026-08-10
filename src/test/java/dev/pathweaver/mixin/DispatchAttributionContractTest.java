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
 * The dispatched request must carry the class of the EVALUATOR, from the navigation's own field.
 *
 * <p>Bytecode, and this is the one place in the feature where that is the right tool rather than a
 * substitute for a real test. The breaker's live game test drives a real server, but it submits its
 * failing searches through {@code pool.submit} with the family named explicitly — so it never
 * exercises what the dispatch site puts in the envelope. Making a real mob's real search throw on
 * demand is not something a game test can arrange.
 *
 * <p>The previous version of this contract asserted only that <em>some</em> {@code getClass()}
 * immediately preceded the constructor. Replacing {@code this.nodeEvaluator.getClass()} with
 * {@code this.getClass()} therefore survived it: the family becomes {@code GroundPathNavigation},
 * {@code allowlistedFamilyOf} returns null, every failure is dropped unattributed, and the breaker is
 * permanently inert in the shipped jar while the whole suite stays green. So this checks the
 * <em>receiver</em>, not just the call: the value handed to the constructor must have come from
 * reading the {@code nodeEvaluator} field.
 */
class DispatchAttributionContractTest {

    @Test
    void theDispatchedRequestCarriesTheEvaluatorFieldsOwnClass() throws Exception {
        int[] constructorsSeen = {0};
        int[] builtFromTheEvaluatorField = {0};

        try (InputStream in = DispatchAttributionContractTest.class
                .getResourceAsStream("/dev/pathweaver/mixin/PathNavigationMixin.class")) {
            assertNotNull(in, "PathNavigationMixin.class not readable");
            new ClassReader(in.readAllBytes()).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                           String signature, String[] exceptions) {
                    return new MethodVisitor(Opcodes.ASM9) {
                        /** The last field read was the navigation's node evaluator. */
                        private boolean lastReadEvaluatorField;
                        /** ...and the value on the stack is that field's class. */
                        private boolean lastWasEvaluatorClass;

                        @Override public void visitFieldInsn(int opcode, String owner, String field,
                                                             String desc) {
                            lastReadEvaluatorField = opcode == Opcodes.GETFIELD
                                && field.equals("nodeEvaluator");
                            lastWasEvaluatorClass = false;
                        }

                        @Override public void visitMethodInsn(int opcode, String owner, String method,
                                                              String desc, boolean isInterface) {
                            if (opcode == Opcodes.INVOKESPECIAL
                                    && owner.equals("dev/pathweaver/async/PathRequest")
                                    && method.equals("<init>")) {
                                constructorsSeen[0]++;
                                if (lastWasEvaluatorClass) builtFromTheEvaluatorField[0]++;
                                lastWasEvaluatorClass = false;
                                lastReadEvaluatorField = false;
                                return;
                            }
                            // getClass() called directly on the value the field read just pushed.
                            lastWasEvaluatorClass = lastReadEvaluatorField
                                && method.equals("getClass") && desc.equals("()Ljava/lang/Class;");
                            lastReadEvaluatorField = false;
                        }

                        // Anything else between the two breaks the chain, which is the property: the
                        // last argument is this field's live class, not a constant, not the
                        // navigation's own class, and not something read earlier and possibly stale.
                        @Override public void visitInsn(int opcode) { clear(); }
                        @Override public void visitVarInsn(int opcode, int var) { clear(); }
                        @Override public void visitLdcInsn(Object value) { clear(); }
                        @Override public void visitTypeInsn(int opcode, String type) { clear(); }

                        private void clear() {
                            lastReadEvaluatorField = false;
                            lastWasEvaluatorClass = false;
                        }
                    };
                }
            }, ClassReader.SKIP_FRAMES);
        }

        assertTrue(constructorsSeen[0] > 0,
            "no PathRequest construction found in the dispatch mixin -- this contract is asserting "
                + "nothing, which is worse than failing");
        assertEquals(constructorsSeen[0], builtFromTheEvaluatorField[0],
            "every dispatched PathRequest must carry `this.nodeEvaluator.getClass()`. Any other "
                + "receiver -- the navigation's own class, a cached copy, a constant -- resolves to "
                + "no allowlisted family, so every worker failure is dropped unattributed and the "
                + "breaker can never trip. Nothing else in the suite can see that: the search still "
                + "runs, the path still installs, and every other test stays green.");
    }
}
