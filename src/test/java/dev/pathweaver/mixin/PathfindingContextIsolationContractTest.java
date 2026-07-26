package dev.pathweaver.mixin;

import dev.pathweaver.async.PathRequest;
import dev.pathweaver.async.PathWeaverThread;
import dev.pathweaver.async.PathWorkerPool;
import dev.pathweaver.async.RequestKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.pathfinder.PathTypeCache;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class PathfindingContextIsolationContractTest {
    @Test void redirectHasExactConstructorTargetAndDistinctWorkerCacheBranch() throws Exception {
        Method redirect = PathfindingContextMixin.class.getDeclaredMethod(
            "pathweaver$isolateCache", ServerLevel.class);
        redirect.setAccessible(true);
        PathfindingContextMixin receiver = new PathfindingContextMixin();

        PathWeaverThread.enterWorker();
        try {
            Object first = redirect.invoke(receiver, new Object[]{null});
            Object second = redirect.invoke(receiver, new Object[]{null});
            assertInstanceOf(PathTypeCache.class, first);
            assertInstanceOf(PathTypeCache.class, second);
            assertNotSame(first, second, "each worker context must receive a fresh cache");
        } finally {
            PathWeaverThread.exitWorker();
        }
        assertFalse(PathWeaverThread.isWorker());

        MethodNode method = redirectMethodBytecode();
        AnnotationNode redirectAnnotation = annotations(method).stream()
            .filter(a -> a.desc.equals("Lorg/spongepowered/asm/mixin/injection/Redirect;"))
            .findFirst().orElseThrow();
        assertEquals(List.of("<init>(Lnet/minecraft/world/level/CollisionGetter;"
            + "Lnet/minecraft/world/entity/Mob;)V"), annotationValue(redirectAnnotation, "method"));
        AnnotationNode at = (AnnotationNode) annotationValue(redirectAnnotation, "at");
        assertEquals("INVOKE", annotationValue(at, "value"));
        assertEquals("Lnet/minecraft/server/level/ServerLevel;getPathTypeCache()"
            + "Lnet/minecraft/world/level/pathfinder/PathTypeCache;",
            annotationValue(at, "target"));
        assertEquals(1, annotationValue(redirectAnnotation, "require"));

        int workerReads = 0;
        int freshCaches = 0;
        int vanillaCacheCalls = 0;
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call
                    && call.owner.equals("dev/pathweaver/async/PathWeaverThread")
                    && call.name.equals("isWorker")) workerReads++;
            if (insn instanceof TypeInsnNode type && type.getOpcode() == org.objectweb.asm.Opcodes.NEW
                    && type.desc.equals("net/minecraft/world/level/pathfinder/PathTypeCache")) freshCaches++;
            if (insn instanceof MethodInsnNode call
                    && call.owner.equals("net/minecraft/server/level/ServerLevel")
                    && call.name.equals("getPathTypeCache")) vanillaCacheCalls++;
        }
        assertEquals(1, workerReads);
        assertEquals(1, freshCaches);
        assertEquals(1, vanillaCacheCalls,
            "non-worker path must preserve the original ServerLevel cache call");
    }

    @Test void pathWorkerPoolMarksEntireSearchAndClearsBeforeCompletion() throws Exception {
        Method redirect = PathfindingContextMixin.class.getDeclaredMethod(
            "pathweaver$isolateCache", ServerLevel.class);
        redirect.setAccessible(true);
        PathfindingContextMixin receiver = new PathfindingContextMixin();
        PathWorkerPool pool = new PathWorkerPool();
        pool.start(1, 1);
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Object> cache = new AtomicReference<>();
        AtomicBoolean markedDuringSearch = new AtomicBoolean();
        AtomicBoolean markedDuringCallback = new AtomicBoolean(true);
        try {
            assertTrue(pool.submit(new PathRequest(new RequestKey(1L, 2L, 3), 0L, () -> {
                markedDuringSearch.set(PathWeaverThread.isWorker());
                cache.set(redirect.invoke(receiver, new Object[]{null}));
                return null;
            }, outcome -> {
                markedDuringCallback.set(PathWeaverThread.isWorker());
                done.countDown();
            })));
            assertTrue(done.await(5, TimeUnit.SECONDS));
            assertTrue(markedDuringSearch.get());
            assertInstanceOf(PathTypeCache.class, cache.get());
            assertFalse(markedDuringCallback.get(), "worker marker must clear before result delivery");
        } finally {
            pool.shutdown();
        }
    }

    private static MethodNode redirectMethodBytecode() throws Exception {
        try (InputStream in = PathfindingContextMixin.class.getResourceAsStream(
                "PathfindingContextMixin.class")) {
            assertNotNull(in);
            ClassNode node = new ClassNode();
            new ClassReader(in).accept(node, 0);
            return node.methods.stream().filter(m -> m.name.equals(
                "pathweaver$isolateCache")).findFirst().orElseThrow();
        }
    }

    private static List<AnnotationNode> annotations(MethodNode method) {
        List<AnnotationNode> result = new ArrayList<>();
        if (method.visibleAnnotations != null) result.addAll(method.visibleAnnotations);
        if (method.invisibleAnnotations != null) result.addAll(method.invisibleAnnotations);
        return result;
    }

    private static Object annotationValue(AnnotationNode annotation, String key) {
        for (int i = 0; i < annotation.values.size(); i += 2) {
            if (key.equals(annotation.values.get(i))) return annotation.values.get(i + 1);
        }
        return null;
    }
}
