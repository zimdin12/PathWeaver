package dev.pathweaver.mixin;

import net.minecraft.world.entity.ai.navigation.PathNavigation;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PathNavigationStalenessContractTest {
    private static final String INJECT = "Lorg/spongepowered/asm/mixin/injection/Inject;";

    @Test void vanillaStopDescriptorExistsExactlyOnce() throws Exception {
        int[] count = {0};
        new ClassReader(classBytes(PathNavigation.class)).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                       String signature, String[] exceptions) {
                if (name.equals("stop") && descriptor.equals("()V")) count[0]++;
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        assertEquals(1, count[0]);
    }

    @Test void stopInvalidationInjectionFailsClosedAtExactHead() throws Exception {
        List<String> methods = new ArrayList<>();
        int[] require = {-1};
        int[] expect = {-1};
        String[] at = {null};
        int[] annotations = {0};
        new ClassReader(classBytes(PathNavigationMixin.class)).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                       String signature, String[] exceptions) {
                if (!name.equals("pathweaver$invalidateStoppedRequest")) return null;
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                        if (!descriptor.equals(INJECT)) return null;
                        annotations[0]++;
                        return new AnnotationVisitor(Opcodes.ASM9) {
                            @Override public void visit(String key, Object value) {
                                if (key.equals("require")) require[0] = (Integer) value;
                                if (key.equals("expect")) expect[0] = (Integer) value;
                            }
                            @Override public AnnotationVisitor visitArray(String key) {
                                if (key.equals("method")) {
                                    return new AnnotationVisitor(Opcodes.ASM9) {
                                        @Override public void visit(String ignored, Object value) {
                                            methods.add((String) value);
                                        }
                                    };
                                }
                                if (key.equals("at")) {
                                    return new AnnotationVisitor(Opcodes.ASM9) {
                                        @Override public AnnotationVisitor visitAnnotation(
                                                String ignored, String desc) {
                                            return new AnnotationVisitor(Opcodes.ASM9) {
                                                @Override public void visit(String atKey, Object value) {
                                                    if (atKey.equals("value")) at[0] = (String) value;
                                                }
                                            };
                                        }
                                    };
                                }
                                return null;
                            }
                        };
                    }
                };
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

        assertEquals(1, annotations[0]);
        assertEquals(List.of("stop()V"), methods);
        assertEquals(1, require[0]);
        assertEquals(1, expect[0]);
        assertEquals("HEAD", at[0]);
    }

    @Test void acceptedPendingPreserveBranchCannotConsultMutableRuntimeToggles() throws Exception {
        boolean[] sawDecision = {false};
        boolean[] sawServerGuardBeforeDecision = {false};
        boolean[] sawSetReturnValue = {false};
        boolean[] sawTerminalReturn = {false};
        List<String> toggleReads = new ArrayList<>();

        new ClassReader(classBytes(PathNavigationMixin.class)).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                       String signature, String[] exceptions) {
                if (!name.equals("pathweaver$asyncCreatePath")) return null;
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override public void visitTypeInsn(int opcode, String type) {
                        if (!sawDecision[0] && opcode == Opcodes.INSTANCEOF
                                && type.equals("net/minecraft/server/level/ServerLevel")) {
                            sawServerGuardBeforeDecision[0] = true;
                        }
                    }

                    @Override public void visitMethodInsn(int opcode, String owner, String method,
                                                          String descriptor, boolean isInterface) {
                        if (owner.equals("dev/pathweaver/async/EntityInstallSink")
                                && method.equals("pendingDecision")) {
                            sawDecision[0] = true;
                        } else if (sawDecision[0] && !sawTerminalReturn[0]
                                && method.equals("setReturnValue")) {
                            sawSetReturnValue[0] = true;
                        }
                    }

                    @Override public void visitFieldInsn(int opcode, String owner, String field,
                                                         String descriptor) {
                        if (sawDecision[0] && !sawTerminalReturn[0]
                                && owner.equals("dev/pathweaver/config/PathWeaverConfig")
                                && field.equals("enabled")) {
                            toggleReads.add(field);
                        }
                    }

                    @Override public void visitInsn(int opcode) {
                        if (sawDecision[0] && opcode == Opcodes.RETURN && !sawTerminalReturn[0]) {
                            sawTerminalReturn[0] = true;
                        }
                    }
                };
            }
        }, 0);

        assertTrue(sawDecision[0], "production injection must classify the live registration");
        assertTrue(sawServerGuardBeforeDecision[0],
            "client navigation must not classify or supersede a server registration with the same entity ID");
        assertTrue(sawSetReturnValue[0], "PRESERVE branch must return the current path");
        assertTrue(sawTerminalReturn[0], "PRESERVE branch must terminate the injection");
        assertEquals(List.of(), toggleReads,
            "mid-flight config toggles must not turn PRESERVE into a sync fallthrough");
    }

    @Test void masterOffFalseEdgeReturnsAndEnabledDominatesElisionAndAsyncEligibility() throws Exception {
        ClassNode type = new ClassNode(Opcodes.ASM9);
        new ClassReader(classBytes(PathNavigationMixin.class)).accept(type, 0);
        MethodNode method = type.methods.stream()
            .filter(m -> m.name.equals("pathweaver$asyncCreatePath"))
            .findFirst().orElseThrow();
        AbstractInsnNode[] insns = method.instructions.toArray();
        int enabled = -1;
        int enabledReads = 0;
        int elision = -1;
        int safety = -1;
        for (int i = 0; i < insns.length; i++) {
            if (insns[i] instanceof FieldInsnNode field
                    && field.owner.equals("dev/pathweaver/config/PathWeaverConfig")) {
                if (field.name.equals("enabled")) {
                    enabled = i;
                    enabledReads++;
                }
                // Path reuse is controlled solely by the tolerance now; the separate boolean was
                // removed because it defaulted to on while the tolerance defaulted to 0, so the
                // feature advertised itself as enabled and was inert.
            } else if (insns[i] instanceof MethodInsnNode call
                    && call.owner.equals("dev/pathweaver/gate/SafetyGate")
                    && call.name.equals("isAllowed")) {
                safety = i;
            } else if (insns[i] instanceof MethodInsnNode call
                    && call.name.equals("pathweaver$reuseExistingPathWithinTolerance")) {
                // The CALL, not the config field it reads. Repath elision moved into a named step,
                // so the field read is no longer in this method -- but the property being asserted
                // is about where elision happens relative to the master switch, and the call site is
                // exactly that. Matching by name also survives edits inside the step, which tracking
                // an operand never did.
                elision = i;
            }
        }
        assertTrue(enabled >= 0, "master Enabled must be read in the routing injection");
        assertEquals(1, enabledReads,
            "routing injection must have one unambiguous master Enabled decision");
        assertTrue(elision >= 0, "path reuse must remain reachable while Enabled is ON");
        // The compensating check for moving elision behind a named call. Ordering survived the
        // extraction; the operand property did not, and without this, deleting the
        // `repathToleranceBlocks <= 0` guard from the step leaves this contract and the whole unit
        // suite green while turning a documented opt-in, off-by-default feature on for everyone.
        assertTrue(elisionStepReadsItsOwnSetting(),
            "the elision step must consult repathToleranceBlocks; if it stops, the feature is no "
                + "longer opt-in and nothing else here would notice");
        assertTrue(elision > enabled,
            "master Enabled must be decided before path reuse, or OFF would still reuse paths");
        assertTrue(safety >= 0, "async eligibility must remain reachable while Enabled is ON");

        int branchIndex = nextOpcode(insns, enabled + 1);
        assertTrue(insns[branchIndex] instanceof JumpInsnNode,
            "Enabled read must immediately control a conditional branch");
        JumpInsnNode branch = (JumpInsnNode) insns[branchIndex];
        int falseEdge = switch (branch.getOpcode()) {
            case Opcodes.IFEQ -> indexOf(insns, branch.label);
            case Opcodes.IFNE -> branchIndex + 1;
            default -> fail("Enabled must branch directly on its boolean value");
        };
        int falseInstruction = nextOpcode(insns, falseEdge);
        assertEquals(Opcodes.RETURN, insns[falseInstruction].getOpcode(),
            "Enabled=false must terminate before any PathWeaver intervention");

        List<BitSet> dominators = dominators(insns);
        assertTrue(dominators.get(falseInstruction).get(enabled),
            "the Enabled read must dominate the master-OFF return");
        assertTrue(dominators.get(elision).get(enabled),
            "the Enabled read must dominate repath elision on every control-flow path");
        assertTrue(dominators.get(safety).get(enabled),
            "the Enabled read must dominate async eligibility/dispatch on every control-flow path");
    }

    private static int nextOpcode(AbstractInsnNode[] insns, int start) {
        for (int i = start; i < insns.length; i++) {
            if (insns[i].getOpcode() >= 0) return i;
        }
        throw new AssertionError("missing executable instruction after " + start);
    }

    private static int indexOf(AbstractInsnNode[] insns, AbstractInsnNode needle) {
        for (int i = 0; i < insns.length; i++) if (insns[i] == needle) return i;
        throw new AssertionError("instruction not found");
    }

    private static List<BitSet> dominators(AbstractInsnNode[] insns) {
        int count = insns.length;
        List<List<Integer>> successors = new ArrayList<>(count);
        List<List<Integer>> predecessors = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            successors.add(new ArrayList<>());
            predecessors.add(new ArrayList<>());
        }
        for (int i = 0; i < count; i++) {
            AbstractInsnNode insn = insns[i];
            int opcode = insn.getOpcode();
            if (insn instanceof JumpInsnNode jump) {
                addEdge(successors, predecessors, i, indexOf(insns, jump.label));
                if (opcode != Opcodes.GOTO && i + 1 < count) addEdge(successors, predecessors, i, i + 1);
            } else if (insn instanceof TableSwitchInsnNode table) {
                addEdge(successors, predecessors, i, indexOf(insns, table.dflt));
                for (LabelNode label : table.labels) addEdge(successors, predecessors, i, indexOf(insns, label));
            } else if (insn instanceof LookupSwitchInsnNode lookup) {
                addEdge(successors, predecessors, i, indexOf(insns, lookup.dflt));
                for (LabelNode label : lookup.labels) addEdge(successors, predecessors, i, indexOf(insns, label));
            } else if (opcode != Opcodes.RETURN && opcode != Opcodes.ARETURN
                    && opcode != Opcodes.IRETURN && opcode != Opcodes.LRETURN
                    && opcode != Opcodes.FRETURN && opcode != Opcodes.DRETURN
                    && opcode != Opcodes.ATHROW && i + 1 < count) {
                addEdge(successors, predecessors, i, i + 1);
            }
        }
        BitSet reachable = new BitSet(count);
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        reachable.set(0);
        queue.add(0);
        while (!queue.isEmpty()) {
            for (int next : successors.get(queue.remove())) {
                if (!reachable.get(next)) {
                    reachable.set(next);
                    queue.add(next);
                }
            }
        }
        List<BitSet> dom = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            BitSet set = new BitSet(count);
            if (i == 0) set.set(0);
            else if (reachable.get(i)) set.or(reachable);
            dom.add(set);
        }
        boolean changed;
        do {
            changed = false;
            for (int i = 1; i < count; i++) {
                if (!reachable.get(i)) continue;
                BitSet next = (BitSet) reachable.clone();
                boolean first = true;
                for (int predecessor : predecessors.get(i)) {
                    if (!reachable.get(predecessor)) continue;
                    if (first) {
                        next = (BitSet) dom.get(predecessor).clone();
                        first = false;
                    } else {
                        next.and(dom.get(predecessor));
                    }
                }
                next.set(i);
                if (!next.equals(dom.get(i))) {
                    dom.set(i, next);
                    changed = true;
                }
            }
        } while (changed);
        return dom;
    }

    private static void addEdge(List<List<Integer>> successors, List<List<Integer>> predecessors,
                                int from, int to) {
        successors.get(from).add(to);
        predecessors.get(to).add(from);
    }

    private static byte[] classBytes(Class<?> type) throws IOException {
        String resource = "/" + type.getName().replace('.', '/') + ".class";
        try (InputStream in = type.getResourceAsStream(resource)) {
            if (in == null) throw new IOException("missing class resource " + resource);
            return in.readAllBytes();
        }
    }

    /**
     * Does the elision step BRANCH on the setting, rather than merely pass it along?
     *
     * <p>Checking only that the field is read is not enough, and a mutation proved it: the step
     * hands {@code repathToleranceBlocks} to {@code RepathTolerance.reusableTarget} as an argument,
     * so deleting the opt-in guard leaves a read behind and a presence check stays green. What makes
     * the feature opt-in is a conditional branch on that value BEFORE the reuse attempt, so that is
     * what this looks for.
     */
    private static boolean elisionStepReadsItsOwnSetting() throws Exception {
        int[] firstRead = {-1};
        int[] reuseCall = {-1};
        int[] branchAfterRead = {-1};
        int[] at = {0};
        new ClassReader(classBytes(PathNavigationMixin.class)).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                       String signature, String[] exceptions) {
                if (!name.equals("pathweaver$reuseExistingPathWithinTolerance")) return null;
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override public void visitFieldInsn(int opcode, String owner, String field,
                                                         String desc) {
                        if (firstRead[0] < 0 && owner.equals("dev/pathweaver/config/PathWeaverConfig")
                                && field.equals("repathToleranceBlocks")) {
                            firstRead[0] = at[0];
                        }
                        at[0]++;
                    }
                    @Override public void visitJumpInsn(int opcode, org.objectweb.asm.Label label) {
                        if (firstRead[0] >= 0 && branchAfterRead[0] < 0) branchAfterRead[0] = at[0];
                        at[0]++;
                    }
                    @Override public void visitMethodInsn(int opcode, String owner, String method,
                                                          String desc, boolean isInterface) {
                        if (reuseCall[0] < 0 && method.equals("reusableTarget")) reuseCall[0] = at[0];
                        at[0]++;
                    }
                    @Override public void visitInsn(int opcode) { at[0]++; }
                    @Override public void visitVarInsn(int opcode, int var) { at[0]++; }
                    @Override public void visitTypeInsn(int opcode, String type) { at[0]++; }
                    @Override public void visitLdcInsn(Object value) { at[0]++; }
                    @Override public void visitIntInsn(int opcode, int operand) { at[0]++; }
                };
            }
        }, ClassReader.SKIP_FRAMES);
        return firstRead[0] >= 0 && branchAfterRead[0] > firstRead[0]
            && (reuseCall[0] < 0 || branchAfterRead[0] < reuseCall[0]);
    }
}
