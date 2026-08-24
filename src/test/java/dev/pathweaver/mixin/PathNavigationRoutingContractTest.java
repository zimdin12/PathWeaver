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
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
        // Injects at recomputePath's canUpdatePath() call, deliberately upstream of the branches
        // where vanilla recomputes nothing. Review has twice proposed moving it down to the
        // createPath call so it only fires when a recompute really happens; that would keep work
        // computed against the pre-change world alive and then install it. See the method's javadoc
        // and PathNavigationRoutingGameTest's airborne case.
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
        // Comfortably past any orchestrator instruction count, so the two methods order correctly.
        final int STEP_BASE = 1_000_000;
        int[] regionInOrchestrator = {-1};
        // The land-registry gate. Six mutations at this call site survived the whole suite,
        // including deleting the gate outright -- because every test asserted the PREDICATE and
        // nothing asserted that dispatch calls it. This walks the shipped bytecode of the dispatch
        // method itself, which is the only thing that cannot be true one hop away.
        int[] landGate = {-1};
        int[] latchRead = {-1};
        boolean[] passesRealBypassFlag = {false};
        int[] registerCall = {-1};
        // Not just "register is called after the gate" -- that still passes when the gate's value is
        // replaced by a constant. The last thing pushed before the call must be a LOCAL VARIABLE
        // load, i.e. the decision itself, not ICONST_0.
        // Two slots, not one. The land-registry decision is no longer the LAST argument pushed to
        // register -- the request origin goes after it -- so tracking only the most recent push
        // watches the wrong operand and this contract fails on a call that is perfectly correct.
        boolean[] lastPushWasVarLoad = {false};
        boolean[] previousPushWasVarLoad = {false};
        boolean[] registerGetsTheDecision = {false};
        new ClassReader(classBytes(PathNavigationMixin.class)).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                       String signature, String[] exceptions) {
                // Both halves of the routing decision. The gates live in the orchestrator and the
                // registration in the step it calls, so ordering has to be asserted across the pair.
                // The step's instructions are numbered after the orchestrator's because the
                // orchestrator provably calls it -- asserted below by requiring that call to exist.
                if (name.equals("pathweaver$dispatchSearch")) instruction[0] = STEP_BASE;
                else if (!name.equals("pathweaver$asyncCreatePath")) return null;
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
                        // Region capture moved into the dispatch step, so the CALL to it is the
                        // earliest point at which a region can be built. Ordering is asserted
                        // against that, and the two assertions below stop this being weaker: the
                        // orchestrator must build no region itself, and the step must build one.
                        if (method.equals("pathweaver$dispatchSearch")) {
                            regionConstruction[0] = instruction[0];
                        }
                        if (owner.equals("dev/pathweaver/gate/SafetyGate")
                                && method.equals("requiresEmptyLandRegistry")) {
                            landGate[0] = instruction[0];
                        }
                        if (owner.equals("dev/pathweaver/gate/FabricLandPathRegistryLatch")
                                && method.equals("allowsWalkDispatch")) {
                            latchRead[0] = instruction[0];
                        }
                        // The bypass argument must come from config, not a hard-coded constant --
                        // passing `true` there disables the gate just as effectively as deleting it.
                        if (owner.equals("dev/pathweaver/config/PathWeaverConfig")
                                && method.equals("bypassesCompatibilityScan")) {
                            passesRealBypassFlag[0] = true;
                        }
                        if (owner.equals("dev/pathweaver/async/EntityInstallSink")
                                && method.equals("register")) {
                            registerCall[0] = instruction[0];
                            registerGetsTheDecision[0] = previousPushWasVarLoad[0];
                        }
                        lastPushWasVarLoad[0] = false;
                        next();
                    }
                    @Override public void visitTypeInsn(int opcode, String type) {
                        if (opcode == Opcodes.NEW
                                && type.equals("net/minecraft/world/level/PathNavigationRegion")) {
                            // Would mean the ORCHESTRATOR captures a region itself. It must not:
                            // region capture belongs behind the dispatch step, after every gate.
                            // Below STEP_BASE means we are in the orchestrator, not the step.
                            if (instruction[0] < STEP_BASE) regionInOrchestrator[0] = instruction[0];
                        }
                        next();
                    }
                    @Override public void visitInsn(int opcode) {
                        previousPushWasVarLoad[0] = lastPushWasVarLoad[0];
                        lastPushWasVarLoad[0] = false;
                        next();
                    }
                    @Override public void visitJumpInsn(int opcode, org.objectweb.asm.Label label) { next(); }
                    @Override public void visitVarInsn(int opcode, int varIndex) {
                        previousPushWasVarLoad[0] = lastPushWasVarLoad[0];
                        lastPushWasVarLoad[0] = opcode == Opcodes.ILOAD;
                        next();
                    }
                };
            }
        }, 0);
        assertTrue(guardRead[0] >= 0, "missing depth-zero guard");
        assertTrue(configRead[0] > guardRead[0], "depth guard must execute before config/elision/dispatch");
        assertTrue(safetyGate[0] > configRead[0], "evaluator family safety must run before mob origin");
        assertTrue(mobOriginGate[0] > safetyGate[0], "missing concrete-mob origin gate");
        assertTrue(regionConstruction[0] > mobOriginGate[0], "mob origin must fail closed before region capture");
        assertEquals(-1, regionInOrchestrator[0],
            "the routing injection must not capture a PathNavigationRegion itself -- every gate "
                + "above exists to refuse before that cost is paid, and building one here would "
                + "bypass them all");
        assertTrue(dispatchStepCapturesTheRegion(),
            "the dispatch step must be where the region is captured; if it moved again, the "
                + "ordering asserted here is measuring the wrong instruction");
        assertTrue(landGate[0] >= 0,
            "dispatch must consult SafetyGate.requiresEmptyLandRegistry -- without this assertion, "
                + "deleting the land-registry gate outright leaves the whole unit suite green");
        assertTrue(latchRead[0] >= 0,
            "dispatch must consult FabricLandPathRegistryLatch.allowsWalkDispatch");
        assertTrue(passesRealBypassFlag[0],
            "the bypass argument must be read from config, not passed as a constant");
        // Real order, read off the bytecode rather than assumed: family safety -> land registry ->
        // mob origin -> region. My first version of this assertion had the last two swapped and the
        // test correctly rejected it.
        assertTrue(landGate[0] > safetyGate[0] && landGate[0] < mobOriginGate[0],
            "the land-registry gate must run after the evaluator-family check and before the mob "
                + "origin gate");
        assertTrue(landGate[0] < regionConstruction[0],
            "a land-registry refusal must cost no region capture");
        assertTrue(registerCall[0] >= STEP_BASE,
            "registration must happen inside the dispatch step, not in the orchestrator, or the "
                + "ordering asserted here is measuring the wrong instruction");
        assertTrue(registerCall[0] > landGate[0],
            "the land-registry decision must be made before the request registers");
        assertTrue(registerGetsTheDecision[0],
            "sink.register must be handed the land-registry DECISION, not a constant -- it arms the "
                + "install-time re-check, and passing false there makes a stale registry "
                + "unobservable while every other assertion still passes");

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

    @Test void dispatchInterceptorNeverNamesTheCompatibilityTierEnum() throws Exception {
        // This mixin is applied to a vanilla class during early transformation. Naming the tier enum
        // in its bytecode forces that enum -- and through it the Cloth GUI interface it implements
        // for its settings label -- to resolve at that moment, which stalls server startup. Both
        // tier-derived decisions must therefore be read through primitive accessors on the config.
        // Enforced against the bytes rather than trusted to a comment, because the comment did not
        // stop it happening the first time.
        Set<String> offendingReferences = new HashSet<>();
        new ClassReader(classBytes(PathNavigationMixin.class)).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                       String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override public void visitFieldInsn(int opcode, String owner, String field,
                                                         String fieldDescriptor) {
                        if (fieldDescriptor.contains("CompatibilityTier")
                                || owner.contains("CompatibilityTier")) {
                            offendingReferences.add(name + " -> field " + owner + "." + field);
                        }
                    }
                    @Override public void visitMethodInsn(int opcode, String owner, String method,
                                                          String methodDescriptor, boolean isInterface) {
                        if (owner.contains("CompatibilityTier")
                                || methodDescriptor.contains("CompatibilityTier")) {
                            offendingReferences.add(name + " -> call " + owner + "." + method);
                        }
                    }
                    @Override public void visitTypeInsn(int opcode, String type) {
                        if (type.contains("CompatibilityTier")) {
                            offendingReferences.add(name + " -> type " + type);
                        }
                    }
                };
            }
        }, 0);
        assertEquals(Set.of(), offendingReferences,
            "read tier-derived decisions through a primitive config accessor instead");

        // And the accessors it does use must stay primitive, or the reference moves rather than goes.
        for (String accessor : List.of("bypassesCompatibilityScan", "moddedMobAsyncAllowed")) {
            assertEquals(boolean.class,
                dev.pathweaver.config.PathWeaverConfig.class.getMethod(accessor).getReturnType(),
                accessor);
        }
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
        boolean stopped;
        @Override public void stop() { stopped = true; this.path = null; }

        @Override protected boolean canUpdatePath() { return true; }
        @Override public boolean moveTo(Path path, double speed) { return false; }
    }


    /**
     * The reuse-check redirect exists, and it is gated on navigation depth.
     *
     * <p>What it prevents: during an in-flight dispatch {@code targetPos} names the destination being
     * searched for while {@code path} still routes to the previous one. Vanilla's short-circuit
     * (offsets 41-75 of the real 26.1.2 {@code createPath}) treats those two as a pair and returns
     * {@code path} when the caller's target set contains {@code targetPos}. A depth-zero query —
     * {@code TargetGoal.canReach} and friends — therefore received a route to the destination the mob
     * had already abandoned, and measured its end node to answer a question about a different place.
     *
     * <p>Bytecode, because the property is about a mixin's shape and no unit test can build a real
     * {@code PathNavigation}. It asserts two things a deletion would break: that the redirect method
     * survives compilation at all, and that it reads the depth field — without that read the redirect
     * would also rewrite our OWN wrapped call sites, which reconcile the pair themselves.
     */
    @Test
    void theReuseCheckRedirectExistsAndIsScopedToDepthZero() throws Exception {
        java.util.Set<String> fieldsRead = new java.util.LinkedHashSet<>();
        boolean[] found = {false};
        try (InputStream in = PathNavigationRoutingContractTest.class
                .getResourceAsStream("/dev/pathweaver/mixin/PathNavigationMixin.class")) {
            assertNotNull(in, "PathNavigationMixin.class not readable");
            new ClassReader(in.readAllBytes()).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                           String signature, String[] exceptions) {
                    if (!name.contains("reuseCheckSeesThePathsOwnTarget")) return null;
                    found[0] = true;
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override public void visitFieldInsn(int opcode, String owner, String field,
                                                             String desc) {
                            fieldsRead.add(field);
                        }
                    };
                }
            }, ClassReader.SKIP_FRAMES);
        }
        assertTrue(found[0],
            "the reuse-check redirect is gone, so vanilla's short-circuit can hand a depth-zero "
                + "query the route to a destination the mob abandoned");
        assertTrue(fieldsRead.stream().anyMatch(f -> f.contains("navigationRequestDepth")),
            "the redirect must consult navigation depth, or it also rewrites our own wrapped call "
                + "sites, which already reconcile the pair: " + fieldsRead);
        assertTrue(fieldsRead.stream().anyMatch(f -> f.contains("targetPosBeforeDispatch")),
            "it must answer with the target the CURRENT path routes to: " + fieldsRead);
    }

    /** Does the extracted dispatch step actually build the region this contract orders against? */
    private static boolean dispatchStepCapturesTheRegion() throws Exception {
        boolean[] found = {false};
        new ClassReader(classBytes(PathNavigationMixin.class)).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                       String signature, String[] exceptions) {
                if (!name.equals("pathweaver$dispatchSearch")) return null;
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override public void visitTypeInsn(int opcode, String type) {
                        if (opcode == Opcodes.NEW
                                && type.equals("net/minecraft/world/level/PathNavigationRegion")) {
                            found[0] = true;
                        }
                    }
                };
            }
        }, ClassReader.SKIP_FRAMES);
        return found[0];
    }
}
