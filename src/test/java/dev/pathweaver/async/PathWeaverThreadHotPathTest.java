package dev.pathweaver.async;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The checks the A* inner loop makes once per node must not cost a ThreadLocal lookup.
 *
 * <p>They are called in every search, including the ones that stay on the server thread. As
 * ThreadLocal reads they took {@code ThreadLocalMap.getEntryAfterMiss} on a crowded server thread and
 * made villagers' point-of-interest searches 50-70% more expensive per call than with no PathWeaver,
 * measured in a 317-mod client. The bug this catches: any of these methods reaching a ThreadLocal,
 * directly or through a helper in this class.
 */
class PathWeaverThreadHotPathTest {

    /** Called from inside the A* loop, per node. */
    private static final Set<String> PER_NODE = Set.of(
        "isWorker", "workerStepHeight", "workerMaxFallDistance", "workerRandom");

    @Test
    void thePerNodeChecksNeverTouchAThreadLocal() throws Exception {
        ClassNode node = classNode();
        List<String> offenders = new ArrayList<>();
        int inspected = 0;
        for (MethodNode method : node.methods) {
            if (!PER_NODE.contains(method.name)) continue;
            inspected++;
            if (reachesThreadLocal(node, method, new java.util.HashSet<>())) offenders.add(method.name);
        }
        assertEquals(PER_NODE.size(), inspected, "every per-node method must exist to be inspected");
        assertEquals(List.of(), offenders, "per-node checks reach a ThreadLocal, which costs a hash-map "
            + "lookup per node in every search, synchronous ones included");
    }

    /** The instrument can say yes: the prologue flag IS a ThreadLocal, and must be found as one. */
    @Test
    void theScanFindsTheThreadLocalThatIsThere() throws Exception {
        ClassNode node = classNode();
        MethodNode prologue = node.methods.stream()
            .filter(m -> m.name.equals("enterAsyncPrologue")).findFirst().orElseThrow();
        assertTrue(reachesThreadLocal(node, prologue, new java.util.HashSet<>()));
    }

    @Test
    void anOrdinaryThreadIsNeverAWorkerAndCannotEnterASearch() {
        assertFalse(PathWeaverThread.isWorker());
        assertNull(PathWeaverThread.workerStepHeight());
        assertNull(PathWeaverThread.workerMaxFallDistance());
        assertThrows(IllegalStateException.class, PathWeaverThread::enterWorker);
        assertThrows(IllegalStateException.class, () -> PathWeaverThread.setWorkerStepHeight(1.0F));
        PathWeaverThread.exitWorker();   // idempotent, and harmless off a worker
    }

    @Test
    void aWorkersValuesAreItsOwnAndClearedValuesStayCleared() throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new PathWeaverThread.Worker(() -> {
            try {
                assertFalse(PathWeaverThread.isWorker(), "a worker is not searching until it enters");
                PathWeaverThread.enterWorker();
                assertTrue(PathWeaverThread.isWorker());
                PathWeaverThread.setWorkerStepHeight(0.6F);
                PathWeaverThread.setWorkerMaxFallDistance(3);
                assertEquals(0.6F, PathWeaverThread.workerStepHeight());
                assertEquals(3, PathWeaverThread.workerMaxFallDistance());
                assertSame(PathWeaverThread.workerRandom(), PathWeaverThread.workerRandom());
                PathWeaverThread.clearWorkerStepHeight();
                PathWeaverThread.clearWorkerMaxFallDistance();
                PathWeaverThread.exitWorker();
                assertFalse(PathWeaverThread.isWorker());
                assertNull(PathWeaverThread.workerStepHeight());
                assertNull(PathWeaverThread.workerMaxFallDistance());
            } catch (Throwable t) {
                failure.set(t);
            }
        }, "test-worker");
        worker.start();
        worker.join(5000);
        assertNull(failure.get(), () -> "worker assertions failed: " + failure.get());
        assertFalse(PathWeaverThread.isWorker(), "the test thread must not see the worker's state");
    }

    private static boolean reachesThreadLocal(ClassNode owner, MethodNode method, java.util.Set<String> seen) {
        if (!seen.add(method.name + method.desc)) return false;
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call) {
                if (call.owner.equals("java/lang/ThreadLocal")) return true;
                if (call.owner.equals(owner.name)) {
                    for (MethodNode m : owner.methods) {
                        if (m.name.equals(call.name) && m.desc.equals(call.desc)
                                && reachesThreadLocal(owner, m, seen)) return true;
                    }
                }
            }
            if (insn instanceof FieldInsnNode field && field.desc.equals("Ljava/lang/ThreadLocal;")) return true;
        }
        return false;
    }

    private static ClassNode classNode() throws Exception {
        try (InputStream in = PathWeaverThread.class.getResourceAsStream("PathWeaverThread.class")) {
            assertNotNull(in);
            ClassNode node = new ClassNode();
            new ClassReader(in).accept(node, 0);
            return node;
        }
    }
}
