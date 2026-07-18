package dev.pathweaver.mixin;

import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.pathfinder.Path;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Locks the exact 26.1.2 virtual-call seams that are allowed to arm async path creation. */
class PathNavigationRoutingContractTest {
    private static final String NAV = "net/minecraft/world/entity/ai/navigation/PathNavigation";
    private static final String PATH = "Lnet/minecraft/world/level/pathfinder/Path;";
    private static final String BLOCK_POS = "Lnet/minecraft/core/BlockPos;";
    private static final String ENTITY = "Lnet/minecraft/world/entity/Entity;";
    private static final String WRAP_OPERATION_DESC =
        "Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;";
    private static final String NAV_TARGET = "L" + NAV + ";";

    private static final Map<String, String> EXPECTED_CALLS = Map.of(
        "recomputePath()V", NAV_TARGET + "createPath(" + BLOCK_POS + "I)" + PATH,
        "moveTo(DDDD)Z", NAV_TARGET + "createPath(DDDI)" + PATH,
        "moveTo(DDDID)Z", NAV_TARGET + "createPath(DDDI)" + PATH,
        "moveTo(" + ENTITY + "D)Z", NAV_TARGET + "createPath(" + ENTITY + "I)" + PATH
    );

    @Test void vanillaNavigationCallersHaveExactlyOneExpectedVirtualCreatePathInvoke() throws Exception {
        Map<String, Map<String, Integer>> calls = collectPathNavigationInvokes(PathNavigation.class);
        for (var expected : EXPECTED_CALLS.entrySet()) {
            assertEquals(Map.of(expected.getValue(), 1), calls.get(expected.getKey()),
                () -> "Minecraft caller drifted: " + expected.getKey());
        }
    }

    @Test void mixinDeclaresOneRequiredWrapOperationPerApprovedCaller() throws Exception {
        List<WrapperAnnotation> annotations = collectWrappers(PathNavigationMixin.class);
        assertEquals(EXPECTED_CALLS.size(), annotations.size(),
            "exactly four raw WrapOperation annotations may arm async");
        Map<String, WrapperAnnotation> wrappers = new HashMap<>();
        for (WrapperAnnotation wrapper : annotations) {
            assertEquals(1, wrapper.methods().size(), "each wrapper must name exactly one caller");
            assertEquals(1, wrapper.atCount(), "each wrapper must contain exactly one @At");
            assertEquals(Set.of("value", "target"), wrapper.atKeys(),
                "@At may contain only the exact invocation kind and target");
            String method = wrapper.methods().getFirst();
            assertTrue(wrappers.putIfAbsent(method, wrapper) == null,
                () -> "duplicate wrapper annotation for " + method);
        }
        for (var expected : EXPECTED_CALLS.entrySet()) {
            WrapperAnnotation wrapper = wrappers.get(expected.getKey());
            assertTrue(wrapper != null, () -> "missing wrapper for " + expected.getKey());
            assertEquals(1, wrapper.require(), expected.getKey() + " must fail closed on mapping drift");
            assertEquals(1, wrapper.expect(), expected.getKey() + " must lock invocation multiplicity");
            assertEquals("INVOKE", wrapper.atValue(), expected.getKey() + " must wrap an invocation");
            assertEquals(expected.getValue(), wrapper.target(), expected.getKey() + " target descriptor drift");
        }
    }

    @Test void routingAndMovementResultInjectionsFailClosedOnMappingDrift() throws Exception {
        assertInjection("pathweaver$asyncCreatePath", 1, 1,
            Set.class, int.class, boolean.class, int.class, float.class,
            CallbackInfoReturnable.class);
        assertInjection("pathweaver$captureCoordinateSpeed", 1, 1,
            double.class, double.class, double.class, double.class, CallbackInfoReturnable.class);
        assertInjection("pathweaver$captureCoordinateReachSpeed", 1, 1,
            double.class, double.class, double.class, int.class, double.class,
            CallbackInfoReturnable.class);
        assertInjection("pathweaver$captureEntitySpeed", 1, 1,
            net.minecraft.world.entity.Entity.class, double.class, CallbackInfoReturnable.class);
        assertInjection("pathweaver$deferredMovementResult", 3, 3, CallbackInfoReturnable.class);
        assertInjection("pathweaver$supersedeBeforeRecomputeGuard", 1, 1,
            org.spongepowered.asm.mixin.injection.callback.CallbackInfo.class);
    }

    @Test void routingGuardsAreOrderedAndEveryWrapperRestoresDepthOnThrow() throws Exception {
        int[] instruction = {0};
        int[] guardRead = {-1};
        int[] configRead = {-1};
        int[] safetyGate = {-1};
        int[] mobOriginGate = {-1};
        int[] regionConstruction = {-1};
        new ClassReader(classBytes(PathNavigationMixin.class)).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                       String signature, String[] exceptions) {
                if (!name.equals("pathweaver$asyncCreatePath")) return null;
                return new MethodVisitor(Opcodes.ASM9) {
                    private void next() { instruction[0]++; }
                    @Override public void visitFieldInsn(int opcode, String owner, String field,
                                                         String fieldDescriptor) {
                        if (opcode == Opcodes.GETFIELD
                                && field.equals("pathweaver$navigationRequestDepth")) {
                            guardRead[0] = instruction[0];
                        }
                        next();
                    }
                    @Override public void visitMethodInsn(int opcode, String owner, String method,
                                                          String methodDescriptor, boolean isInterface) {
                        if (owner.equals("dev/pathweaver/config/PathWeaverConfig")
                                && method.equals("get")) {
                            configRead[0] = instruction[0];
                        }
                        if (owner.equals("dev/pathweaver/gate/SafetyGate")
                                && method.equals("isAllowed")) {
                            safetyGate[0] = instruction[0];
                        }

                        if (owner.equals("dev/pathweaver/gate/MobOriginGate")
                                && method.equals("isAllowed")) {
                            mobOriginGate[0] = instruction[0];
                        }
                        next();
                    }
                    @Override public void visitTypeInsn(int opcode, String type) {
                        if (opcode == Opcodes.NEW
                                && type.equals("net/minecraft/world/level/PathNavigationRegion")) {
                            regionConstruction[0] = instruction[0];
                        }
                        next();
                    }
                    @Override public void visitInsn(int opcode) { next(); }
                    @Override public void visitJumpInsn(int opcode, org.objectweb.asm.Label label) { next(); }
                    @Override public void visitVarInsn(int opcode, int varIndex) { next(); }
                };
            }
        }, 0);
        assertTrue(guardRead[0] >= 0, "missing depth-zero guard");
        assertTrue(configRead[0] > guardRead[0], "depth guard must execute before config/elision/dispatch");
        assertTrue(safetyGate[0] > configRead[0], "evaluator family safety must run before mob origin");
        assertTrue(mobOriginGate[0] > safetyGate[0], "missing concrete-mob origin gate");
        assertTrue(regionConstruction[0] > mobOriginGate[0], "mob origin must fail closed before region capture");

        PathNavigationMixin mixin = new TestNavigationMixin();
        var depth = PathNavigationMixin.class.getDeclaredField("pathweaver$navigationRequestDepth");
        depth.setAccessible(true);
        RuntimeException expected = new RuntimeException("expected");
        int wrapperCount = 0;
        for (var wrapper : PathNavigationMixin.class.getDeclaredMethods()) {
            if (!wrapper.getName().startsWith("pathweaver$arm")) continue;
            wrapperCount++;
            wrapper.setAccessible(true);
            Object[] args = defaultWrapperArguments(wrapper.getParameterTypes());
            args[args.length - 1] = (com.llamalad7.mixinextras.injector.wrapoperation.Operation<Object>)
                ignored -> null;
            wrapper.invoke(mixin, args);
            assertEquals(0, depth.getInt(mixin), wrapper.getName() + " normal exit");

            args[args.length - 1] = (com.llamalad7.mixinextras.injector.wrapoperation.Operation<Object>)
                ignored -> { throw expected; };
            var thrown = org.junit.jupiter.api.Assertions.assertThrows(
                java.lang.reflect.InvocationTargetException.class, () -> wrapper.invoke(mixin, args));
            assertEquals(expected, thrown.getCause(), wrapper.getName());
            assertEquals(0, depth.getInt(mixin), wrapper.getName() + " exceptional exit");
        }
        assertEquals(4, wrapperCount, "all navigation routing wrappers must be exercised");
    }

    private static void assertInjection(String name, int require, int expect, Class<?>... parameters)
            throws NoSuchMethodException {
        Inject inject = PathNavigationMixin.class.getDeclaredMethod(name, parameters)
            .getAnnotation(Inject.class);
        assertTrue(inject != null, name + " must remain an @Inject seam");
        assertEquals(require, inject.require(), name + " require");
        assertEquals(expect, inject.expect(), name + " expect");
    }

    private static Object[] defaultWrapperArguments(Class<?>[] parameterTypes) {
        Object[] args = new Object[parameterTypes.length];
        for (int i = 0; i < parameterTypes.length; i++) {
            if (parameterTypes[i] == int.class) args[i] = 0;
            else if (parameterTypes[i] == double.class) args[i] = 0.0;
        }
        return args;
    }

    private static Map<String, Map<String, Integer>> collectPathNavigationInvokes(Class<?> type)
            throws IOException {
        Map<String, Map<String, Integer>> result = new HashMap<>();
        new ClassReader(classBytes(type)).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                       String signature, String[] exceptions) {
                String caller = name + descriptor;
                if (!EXPECTED_CALLS.containsKey(caller)) return null;
                Map<String, Integer> calls = result.computeIfAbsent(caller, ignored -> new HashMap<>());
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override public void visitMethodInsn(int opcode, String owner, String calledName,
                                                          String calledDescriptor, boolean isInterface) {
                        if (opcode == Opcodes.INVOKEVIRTUAL && owner.equals(NAV)
                                && calledName.equals("createPath")) {
                            calls.merge("L" + owner + ";" + calledName + calledDescriptor, 1, Integer::sum);
                        }
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return result;
    }

    private static List<WrapperAnnotation> collectWrappers(Class<?> type) throws IOException {
        List<WrapperAnnotation> result = new ArrayList<>();
        new ClassReader(classBytes(type)).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                       String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override public AnnotationVisitor visitAnnotation(String annotation, boolean visible) {
                        if (!annotation.equals(WRAP_OPERATION_DESC)) return null;
                        MutableWrapper wrapper = new MutableWrapper();
                        return new AnnotationVisitor(Opcodes.ASM9) {
                            @Override public void visit(String key, Object value) {
                                if (key.equals("require")) wrapper.require = (Integer) value;
                                if (key.equals("expect")) wrapper.expect = (Integer) value;
                            }
                            @Override public AnnotationVisitor visitArray(String key) {
                                if (key.equals("method")) {
                                    return new AnnotationVisitor(Opcodes.ASM9) {
                                        @Override public void visit(String ignored, Object value) {
                                            wrapper.methods.add((String) value);
                                        }
                                    };
                                }
                                if (key.equals("at")) {
                                    return new AnnotationVisitor(Opcodes.ASM9) {
                                        @Override public AnnotationVisitor visitAnnotation(
                                                String ignored, String desc) {
                                            wrapper.atCount++;
                                            return atTargetVisitor(wrapper);
                                        }
                                    };
                                }
                                return super.visitArray(key);
                            }
                            @Override public void visitEnd() {
                                result.add(new WrapperAnnotation(List.copyOf(wrapper.methods),
                                    wrapper.require, wrapper.expect, wrapper.atCount,
                                    Set.copyOf(wrapper.atKeys), wrapper.atValue, wrapper.target));
                            }
                        };
                    }
                };
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return result;
    }

    private static AnnotationVisitor atTargetVisitor(MutableWrapper wrapper) {
        return new AnnotationVisitor(Opcodes.ASM9) {
            @Override public void visit(String key, Object value) {
                wrapper.atKeys.add(key);
                if (key.equals("value")) wrapper.atValue = (String) value;
                if (key.equals("target")) wrapper.target = (String) value;
            }
        };
    }

    private static byte[] classBytes(Class<?> type) throws IOException {
        String resource = "/" + type.getName().replace('.', '/') + ".class";
        try (InputStream in = type.getResourceAsStream(resource)) {
            if (in == null) throw new IOException("missing class resource " + resource);
            return in.readAllBytes();
        }
    }

    private record WrapperAnnotation(List<String> methods, int require, int expect,
                                     int atCount, Set<String> atKeys,
                                     String atValue, String target) {}
    private static final class MutableWrapper {
        final List<String> methods = new ArrayList<>();
        final Set<String> atKeys = new HashSet<>();
        int atCount;
        String atValue;
        String target;
        int require = -1;
        int expect = -1;
    }

    private static final class TestNavigationMixin extends PathNavigationMixin {
        @Override protected boolean canUpdatePath() { return true; }
        @Override public boolean moveTo(Path path, double speed) { return false; }
    }
}
