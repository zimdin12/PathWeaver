package dev.pathweaver.gate;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The methods of a watched class that a search can actually call, derived from Minecraft's bytecode.
 *
 * <p>Half of the narrowing. {@link MixinClaimMethods} answers "which method does this mod inject
 * into"; this answers "can a worker reach that method at all". A claim is only dangerous when both
 * say yes.
 *
 * <p>It is derived rather than listed on purpose. Every previous version of this idea in this project
 * was a hand-written set that turned out to be one entry short —
 * {@code SHARED_PATHFINDING_TARGETS} stopped a call before the chunk read,
 * {@code WalkNodeEvaluatorMixin} enumerated two call sites and missed a subclass override,
 * {@code CONFINED_MOB_READS} enumerated read names and missed {@code getMaxFallDistance}. A list is
 * exactly as good as the moment it was written.
 *
 * <h2>Which way it fails</h2>
 *
 * <p>Closed, and generously so, because the answer is used to CLEAR a claim:
 *
 * <ul>
 *   <li>The walk starts at every method of every admitted evaluator plus {@code PathFinder} and
 *       follows calls through {@code net.minecraft} to a bounded depth, collecting every method
 *       invoked on the watched class along the way.
 *   <li>Any class that cannot be read, or a walk that hits the depth bound with work outstanding,
 *       makes the whole answer {@link Optional#empty()} — the caller then denies exactly as before.
 *   <li>Virtual calls contribute the declared method name, and names are compared without
 *       descriptors, so an overload or an override of the same name is covered by the same entry.
 * </ul>
 *
 * <p>It is not a proof. Reflection, {@code invokedynamic} and anything leaving
 * {@code net.minecraft} are invisible, and a mod could reach a watched method by a route no vanilla
 * bytecode takes. That is why this narrows an existing deny-by-default gate rather than replacing it.
 */
final class WorkerReachableMethods {

    /** Bounded so a pathological hierarchy cannot stall startup; exceeding it fails closed. */
    private static final int MAX_METHODS_WALKED = 20_000;

    private static final Map<String, Optional<Set<String>>> CACHE = new ConcurrentHashMap<>();

    private WorkerReachableMethods() {}

    /**
     * Method names of {@code watchedInternalName} that a search can invoke.
     *
     * @param watchedInternalName e.g. {@code net/minecraft/world/level/block/state/BlockBehaviour$BlockStateBase}
     */
    static Optional<Set<String>> on(String watchedInternalName) {
        return CACHE.computeIfAbsent(watchedInternalName, WorkerReachableMethods::compute);
    }

    static void clearCacheForTests() {
        CACHE.clear();
    }

    private static Optional<Set<String>> compute(String watched) {
        Set<String> reached = new LinkedHashSet<>();
        Set<String> visited = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();

        for (Class<?> evaluator : SafetyGate.allowlisted()) {
            queue.add(evaluator.getName().replace('.', '/'));
        }
        queue.add("net/minecraft/world/level/pathfinder/PathFinder");
        queue.add("net/minecraft/world/level/pathfinder/PathfindingContext");

        int walked = 0;
        while (!queue.isEmpty()) {
            if (++walked > MAX_METHODS_WALKED) return Optional.empty();
            String owner = queue.poll();
            if (!visited.add(owner)) continue;
            byte[] bytes = bytesOf(owner);
            if (bytes == null) {
                // A class on the search's own call graph that cannot be read means the answer is
                // incomplete, and an incomplete answer must not clear anybody's claim.
                return Optional.empty();
            }
            Set<String> nextOwners = new LinkedHashSet<>();
            try {
                new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
                    @Override
                    public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                     String signature, String[] exceptions) {
                        return new MethodVisitor(Opcodes.ASM9) {
                            @Override
                            public void visitMethodInsn(int opcode, String callOwner, String called,
                                                        String calledDescriptor, boolean isInterface) {
                                if (callOwner.equals(watched)) reached.add(called);
                                if (callOwner.startsWith("net/minecraft/")
                                        && FOLLOWED.stream().anyMatch(callOwner::startsWith)) {
                                    nextOwners.add(callOwner);
                                }
                            }
                        };
                    }
                }, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
            } catch (RuntimeException malformed) {
                return Optional.empty();
            }
            queue.addAll(nextOwners);
        }
        return Optional.of(Set.copyOf(reached));
    }

    /**
     * Packages the walk follows.
     *
     * <p>Bounded deliberately. Following everything reachable from {@code BlockGetter} drags in
     * world mutation, explosions and the client renderer — measured at 14,944 methods and 3,174
     * classes, which is an answer so wide it clears nothing and means nothing. These are the packages
     * a search's own code actually lives in.
     */
    private static final List<String> FOLLOWED = List.of(
        "net/minecraft/world/level/pathfinder/",
        "net/minecraft/world/level/PathNavigationRegion",
        "net/minecraft/world/entity/ai/navigation/",
        "net/minecraft/world/level/block/state/");

    private static byte[] bytesOf(String internalName) {
        String resource = "/" + internalName + ".class";
        try (InputStream in = WorkerReachableMethods.class.getResourceAsStream(resource)) {
            if (in != null) return in.readAllBytes();
        } catch (IOException ignored) {
            return null;
        }
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        if (loader == null) return null;
        try (InputStream in = loader.getResourceAsStream(internalName + ".class")) {
            return in == null ? null : in.readAllBytes();
        } catch (IOException ignored) {
            return null;
        }
    }
}
