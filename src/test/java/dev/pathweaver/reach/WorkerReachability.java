package dev.pathweaver.reach;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * What a worker can actually reach, derived rather than listed.
 *
 * <p>Three releases in a row shipped a defect with the same shape: an audit that was a hand-written
 * list, and the list was one entry short. {@code SHARED_PATHFINDING_TARGETS} stopped one call before
 * the chunk read; {@code WalkNodeEvaluatorMixin} enumerated two call sites and missed a subclass
 * override; {@code CONFINED_MOB_READS} enumerates read <em>names</em> and missed
 * {@code getMaxFallDistance}. Every one of those was found by a human reading code, after release.
 *
 * <p>So this does not ask "is this name on the list". It starts from the methods a search actually
 * enters and walks the call graph until it either runs out of Minecraft code or reaches something
 * that is unsafe to touch from a worker thread.
 *
 * <h2>Which direction it errs</h2>
 *
 * <p>Over-approximation, deliberately, and the same direction serves both users. For hazard
 * detection, missing an edge means missing a race. For deciding that a foreign mixin is harmless,
 * missing an edge means calling something safe that is not. Both fail the same way, so every
 * ambiguity resolves towards "assume reachable":
 *
 * <ul>
 *   <li>A virtual or interface call expands to the declared method <em>and every override of it</em>
 *       within the scanned packages, because the receiver's runtime type is not known statically.
 *   <li>A method whose bytes cannot be read is recorded as unresolved rather than skipped, so the
 *       caller can fail closed instead of silently concluding "no path found".
 * </ul>
 *
 * <p>It is not a proof. Reflection, {@code invokedynamic} bootstraps and anything crossing out of
 * {@code net.minecraft} are invisible to it, which is exactly why the runtime gate must keep failing
 * closed rather than treating a clean result as permission.
 */
public final class WorkerReachability {

    /** A method, keyed the way the JVM keys it: overloads are different methods. */
    public record MethodRef(String owner, String name, String descriptor) {
        @Override public String toString() { return owner + "." + name + descriptor; }
    }

    /** Somewhere it is not safe for a worker to end up. */
    public record Sink(String owner, String name, String why) {}

    /** One reachable call site that lands on a sink, with the chain that got there. */
    public record Hazard(MethodRef site, Sink sink, List<MethodRef> chain) {
        @Override public String toString() {
            return sink.name() + " via " + chain.stream().map(MethodRef::toString)
                .reduce((a, b) -> a + " -> " + b).orElse("?");
        }
    }

    public record Result(Set<MethodRef> reachable, List<Hazard> hazards, Set<String> unresolved) {}

    /**
     * The things a search must never touch on a worker.
     *
     * <p>Named by what they ARE rather than by who calls them — that is the whole point. Each is a
     * genuine read-modify-write or a shared mutable object, verified against 26.1.2 bytecode:
     * {@code AttributeInstance.getValue} is
     * {@code if (dirty) { cachedValue = calculateValue(); dirty = false; }} over plain non-volatile
     * fields; {@code SynchedEntityData} and {@code RandomSource} are shared live state.
     */
    public static final List<Sink> SINKS = List.of(
        new Sink("net/minecraft/world/entity/ai/attributes/AttributeInstance", "getValue",
            "read-modify-write over non-volatile dirty/cachedValue"),
        new Sink("net/minecraft/world/entity/ai/attributes/AttributeInstance", "getModifier",
            "walks the live modifier collections"),
        new Sink("net/minecraft/network/syncher/SynchedEntityData", "get",
            "live synched entity state, mutated on the main thread"),
        new Sink("net/minecraft/util/RandomSource", "*",
            "shared randomness; advancing it from a worker races the entity tick")
    );

    /**
     * The only world views a worker is ever handed.
     *
     * <p>Without this the analysis is useless rather than merely imprecise, and the failure is
     * instructive: expanding {@code BlockGetter.getBlockEntity} to every implementor reaches
     * {@code WorldGenRegion}, which reaches {@code LevelChunk.setBlockEntity}, which reaches block
     * entity load, explosions, and the client renderer. Measured: 14,944 reachable methods, 3,174
     * classes and 1,220 "hazards", nearly all of them nonsense.
     *
     * <p>They are nonsense because a worker cannot obtain those receivers. PathWeaver constructs the
     * region itself and hands it to the search, so the concrete type is known — this is a fact about
     * PathWeaver's own dispatch, not a guess about Minecraft. Constraining the receiver where the
     * dispatch code proves the type is what makes the rest of the over-approximation affordable.
     */
    private static final Map<String, Set<String>> RECEIVER_UNIVERSE = Map.of(
        "net/minecraft/world/level/BlockGetter",
            Set.of("net/minecraft/world/level/PathNavigationRegion"),
        "net/minecraft/world/level/LevelReader",
            Set.of("net/minecraft/world/level/PathNavigationRegion"),
        "net/minecraft/world/level/CollisionGetter",
            Set.of("net/minecraft/world/level/PathNavigationRegion"),
        "net/minecraft/world/level/LevelAccessor", Set.of(),
        "net/minecraft/world/level/LevelWriter", Set.of(),
        "net/minecraft/world/level/Level", Set.of(),
        "net/minecraft/server/level/ServerLevel", Set.of());

    private final Map<String, ClassNode> classes = new HashMap<>();
    private final Map<String, List<String>> subclasses = new HashMap<>();
    private final Set<String> unresolved = new LinkedHashSet<>();
    private final List<Path> roots;
    private final List<String> scanPrefixes;

    private WorkerReachability(List<Path> roots, List<String> scanPrefixes) {
        this.roots = roots;
        this.scanPrefixes = scanPrefixes;
    }

    public static WorkerReachability over(List<Path> roots, List<String> scanPrefixes) {
        return new WorkerReachability(roots, scanPrefixes);
    }

    /** Index every class under the scan prefixes so virtual calls can expand to their overrides. */
    public WorkerReachability index() throws IOException {
        for (Path root : roots) {
            if (Files.isDirectory(root)) {
                indexTree(root, root);
            } else {
                try (FileSystem fs = FileSystems.newFileSystem(
                        URI.create("jar:" + root.toUri()), Map.of())) {
                    for (Path dir : fs.getRootDirectories()) indexTree(dir, dir);
                }
            }
        }
        for (ClassNode cn : classes.values()) {
            if (cn.superName != null) {
                subclasses.computeIfAbsent(cn.superName, k -> new ArrayList<>()).add(cn.name);
            }
            for (String itf : cn.interfaces) {
                subclasses.computeIfAbsent(itf, k -> new ArrayList<>()).add(cn.name);
            }
        }
        return this;
    }

    private void indexTree(Path base, Path dir) throws IOException {
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) walk.filter(f -> f.toString().endsWith(".class"))::iterator) {
                String rel = base.relativize(p).toString().replace('\\', '/');
                String name = rel.substring(0, rel.length() - ".class".length());
                if (scanPrefixes.stream().noneMatch(name::startsWith)) continue;
                try (InputStream in = Files.newInputStream(p)) {
                    ClassNode cn = new ClassNode();
                    new ClassReader(in.readAllBytes()).accept(cn, ClassReader.SKIP_FRAMES);
                    classes.put(cn.name, cn);
                } catch (IOException | RuntimeException e) {
                    unresolved.add(name + " (unreadable: " + e + ")");
                }
            }
        }
    }

    public int indexedClassCount() { return classes.size(); }

    /** Walk forward from the entry points and report everything reachable, plus every sink hit. */
    /** Call edges PathWeaver severs on a worker: (declaring method) -> (called method name). */
    private Set<String> cutEdges = Set.of();

    /**
     * Tell the analysis which edges PathWeaver already confines.
     *
     * <p>Without this it analyses vanilla as if unmodified and reports hazards the mod has already
     * fixed — every {@code maxUpStep} chain, for instance, which is redirected to a captured value.
     * The interesting question is not "what does vanilla reach" but "what does vanilla still reach
     * AFTER confinement", and the answer should be nothing.
     */
    public WorkerReachability cutting(Set<String> edges) {
        this.cutEdges = Set.copyOf(edges);
        return this;
    }

    private boolean isCut(MethodRef from, MethodInsnNode call) {
        return cutEdges.contains(from.owner() + "#" + from.name() + from.descriptor()
            + " -> " + call.name);
    }

    public Result reachableFrom(Collection<MethodRef> entryPoints) {
        Set<MethodRef> seen = new LinkedHashSet<>();
        List<Hazard> hazards = new ArrayList<>();
        Map<MethodRef, MethodRef> cameFrom = new LinkedHashMap<>();
        Deque<MethodRef> queue = new ArrayDeque<>(entryPoints);
        seen.addAll(entryPoints);

        while (!queue.isEmpty()) {
            MethodRef current = queue.poll();
            MethodNode body = bodyOf(current);
            if (body == null) continue;
            for (AbstractInsnNode insn : body.instructions) {
                if (!(insn instanceof MethodInsnNode call)) continue;
                if (isCut(current, call)) continue;
                Sink sink = sinkFor(call);
                if (sink != null) {
                    hazards.add(new Hazard(current, sink, chainTo(current, cameFrom, entryPoints)));
                }
                for (MethodRef target : expand(call)) {
                    if (seen.add(target)) {
                        cameFrom.put(target, current);
                        queue.add(target);
                    }
                }
            }
        }
        return new Result(seen, hazards, Set.copyOf(unresolved));
    }

    private List<MethodRef> chainTo(MethodRef end, Map<MethodRef, MethodRef> cameFrom,
                                     Collection<MethodRef> entries) {
        List<MethodRef> chain = new ArrayList<>();
        MethodRef at = end;
        Set<MethodRef> guard = new HashSet<>();
        while (at != null && guard.add(at)) {
            chain.add(0, at);
            if (entries.contains(at)) break;
            at = cameFrom.get(at);
        }
        return List.copyOf(chain);
    }

    private Sink sinkFor(MethodInsnNode call) {
        for (Sink s : SINKS) {
            if (!call.owner.equals(s.owner())) continue;
            if (s.name().equals("*") || s.name().equals(call.name)) return s;
        }
        return null;
    }

    /**
     * A call site expands to every method it could actually land on.
     *
     * <p>For a static or special call that is one method. For a virtual or interface call the
     * receiver's runtime type is unknown, so it is the declared method plus every override reachable
     * through the subclass index — the case that made {@code AmphibiousNodeEvaluator} invisible to a
     * guard that had only ever considered {@code WalkNodeEvaluator}.
     */
    private List<MethodRef> expand(MethodInsnNode call) {
        if (scanPrefixes.stream().noneMatch(call.owner::startsWith)) return List.of();
        List<MethodRef> out = new ArrayList<>();
        MethodRef declared = new MethodRef(call.owner, call.name, call.desc);
        out.add(declared);
        if (call.getOpcode() == Opcodes.INVOKEVIRTUAL
                || call.getOpcode() == Opcodes.INVOKEINTERFACE) {
            Set<String> universe = RECEIVER_UNIVERSE.get(call.owner);
            if (universe != null) {
                // The dispatch code proves what the receiver is; do not invent others.
                out.clear();
                for (String concrete : universe) {
                    out.add(new MethodRef(concrete, call.name, call.desc));
                }
                return out;
            }
            collectOverrides(call.owner, call.name, call.desc, out, new HashSet<>());
        }
        return out;
    }

    private void collectOverrides(String owner, String name, String desc, List<MethodRef> out,
                                  Set<String> seen) {
        for (String sub : subclasses.getOrDefault(owner, List.of())) {
            if (!seen.add(sub)) continue;
            ClassNode cn = classes.get(sub);
            if (cn != null) {
                for (MethodNode m : cn.methods) {
                    if (m.name.equals(name) && m.desc.equals(desc)) {
                        out.add(new MethodRef(sub, name, desc));
                    }
                }
            }
            collectOverrides(sub, name, desc, out, seen);
        }
    }

    /** Resolve a method body, walking up the superclass chain the way the JVM would. */
    private MethodNode bodyOf(MethodRef ref) {
        String owner = ref.owner();
        Set<String> guard = new HashSet<>();
        while (owner != null && guard.add(owner)) {
            ClassNode cn = classes.get(owner);
            if (cn == null) {
                if (scanPrefixes.stream().anyMatch(owner::startsWith)) {
                    unresolved.add(owner + " (not indexed)");
                }
                return null;
            }
            for (MethodNode m : cn.methods) {
                if (m.name.equals(ref.name()) && m.desc.equals(ref.descriptor())) {
                    return (m.access & Opcodes.ACC_ABSTRACT) != 0 ? null : m;
                }
            }
            owner = cn.superName;
        }
        return null;
    }

    /** Every declared method of a class, as entry points. */
    public List<MethodRef> allMethodsOf(String internalName) {
        ClassNode cn = classes.get(internalName);
        if (cn == null) return List.of();
        List<MethodRef> out = new ArrayList<>();
        for (MethodNode m : cn.methods) out.add(new MethodRef(cn.name, m.name, m.desc));
        return out;
    }
}
