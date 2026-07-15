package dev.pathweaver.mixin;

import net.minecraft.world.entity.ai.navigation.PathNavigation;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
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
                                && (field.equals("asyncEnabled") || field.equals("syncFallbackOnly"))) {
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

    private static byte[] classBytes(Class<?> type) throws IOException {
        String resource = "/" + type.getName().replace('.', '/') + ".class";
        try (InputStream in = type.getResourceAsStream(resource)) {
            if (in == null) throw new IOException("missing class resource " + resource);
            return in.readAllBytes();
        }
    }
}
