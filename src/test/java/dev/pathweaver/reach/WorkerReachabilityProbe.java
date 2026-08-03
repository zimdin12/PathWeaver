package dev.pathweaver.reach;

import dev.pathweaver.gate.SafetyGate;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Measurement, not a contract. Prints the size and shape of the worker-reachable surface so the
 * design that consumes it is chosen from numbers rather than from a guess.
 */
class WorkerReachabilityProbe {

    /**
     * The edges PathWeaver's shipped mixins sever on a worker thread.
     *
     * <p>Hand-maintained, which is the weakness the analysis exists to remove and which it has
     * already reproduced once: this list named {@code FlyNodeEvaluator#getStart} when the shipped
     * redirect targets {@code iteratePathfindingStartNodeCandidatePositions}. Deriving it from
     * {@code pathweaver.mixins.json} is roadmap work; until then it is shared by the measurement and
     * the assertion below so the two cannot at least disagree with each other.
     */
    private static final Set<String> CUTS = Set.of(
            "net/minecraft/world/level/pathfinder/WalkNodeEvaluator#getNeighbors([Lnet/minecraft/world/level/pathfinder/Node;Lnet/minecraft/world/level/pathfinder/Node;)I -> maxUpStep",
            "net/minecraft/world/level/pathfinder/WalkNodeEvaluator#getMobJumpHeight()D -> maxUpStep",
            "net/minecraft/world/level/pathfinder/WalkNodeEvaluator#tryFindFirstGroundNodeBelow(III)Lnet/minecraft/world/level/pathfinder/Node; -> getMaxFallDistance",
            "net/minecraft/world/level/pathfinder/AmphibiousNodeEvaluator#getNeighbors([Lnet/minecraft/world/level/pathfinder/Node;Lnet/minecraft/world/level/pathfinder/Node;)I -> maxUpStep",
            // NOT getStart: javap puts the only Mob.getRandom() in FlyNodeEvaluator inside
            // iteratePathfindingStartNodeCandidatePositions, which is what the shipped
            // @Redirect targets. The list was one entry wrong -- the precise failure this whole
            // analysis exists to end, reproduced inside the analysis itself.
            "net/minecraft/world/level/pathfinder/FlyNodeEvaluator#iteratePathfindingStartNodeCandidatePositions(Lnet/minecraft/world/entity/Mob;)Ljava/lang/Iterable; -> getRandom",
            "net/minecraft/world/level/pathfinder/WalkNodeEvaluator#prepare(Lnet/minecraft/world/level/PathNavigationRegion;Lnet/minecraft/world/entity/Mob;)V -> onPathfindingStart",
            "net/minecraft/world/level/pathfinder/WalkNodeEvaluator#done()V -> onPathfindingDone",
            "net/minecraft/world/level/pathfinder/FlyNodeEvaluator#prepare(Lnet/minecraft/world/level/PathNavigationRegion;Lnet/minecraft/world/entity/Mob;)V -> onPathfindingStart",
            "net/minecraft/world/level/pathfinder/FlyNodeEvaluator#done()V -> onPathfindingDone");


    static List<Path> minecraftRoots() throws Exception {
        Set<Path> roots = new LinkedHashSet<>();
        for (Class<?> c : List.of(net.minecraft.world.level.pathfinder.WalkNodeEvaluator.class,
                                  net.minecraft.world.entity.Mob.class)) {
            var src = c.getProtectionDomain().getCodeSource();
            if (src != null && src.getLocation() != null) {
                roots.add(Paths.get(src.getLocation().toURI()));
            }
        }
        return List.copyOf(roots);
    }

    @Test
    void measureTheWorkerReachableSurface() throws Exception {
        List<String> prefixes = List.of("net/minecraft/");
        List<Path> roots = minecraftRoots();
        System.out.println("== roots ==");
        roots.forEach(r -> System.out.println("   " + r));

        long t0 = System.nanoTime();
        WorkerReachability reach = WorkerReachability.over(roots, prefixes).index();
        long indexMs = (System.nanoTime() - t0) / 1_000_000;
        System.out.println("indexed classes = " + reach.indexedClassCount() + " in " + indexMs + "ms");

        List<WorkerReachability.MethodRef> entries = new ArrayList<>();
        for (Class<?> evaluator : SafetyGate.allowlisted()) {
            entries.addAll(reach.allMethodsOf(evaluator.getName().replace('.', '/')));
        }
        entries.addAll(reach.allMethodsOf("net/minecraft/world/level/pathfinder/PathFinder"));
        System.out.println("entry points = " + entries.size()
            + " (from " + SafetyGate.allowlisted().size() + " evaluators + PathFinder)");

        reach.cutting(CUTS);

        long t1 = System.nanoTime();
        WorkerReachability.Result r = reach.reachableFrom(entries);
        long walkMs = (System.nanoTime() - t1) / 1_000_000;

        System.out.println("reachable methods = " + r.reachable().size() + " in " + walkMs + "ms");
        System.out.println("unresolved        = " + r.unresolved().size());
        System.out.println("HAZARDS           = " + r.hazards().size());

        java.util.Set<String> uniq = new java.util.TreeSet<>();
        for (WorkerReachability.Hazard h : r.hazards()) {
            uniq.add(h.sink().name() + "  @  " + h.site());
        }
        System.out.println("== distinct (sink, call site) pairs = " + uniq.size() + " ==");
        uniq.forEach(u -> System.out.println("   " + u));

        System.out.println("== shortest chain per pair ==");
        Map<String, WorkerReachability.Hazard> shortest = new LinkedHashMap<>();
        for (WorkerReachability.Hazard h : r.hazards()) {
            String k = h.sink().name() + "  @  " + h.site();
            var cur = shortest.get(k);
            if (cur == null || h.chain().size() < cur.chain().size()) shortest.put(k, h);
        }
        shortest.values().stream()
            .sorted(java.util.Comparator.comparingInt(h -> h.chain().size()))
            .forEach(h -> System.out.println("   [" + h.chain().size() + "] " + h));

        // How many DISTINCT owning classes hold a reachable method? That is the real
        // "sensitive surface" the foreign-mixin gate should be watching.
        Set<String> owners = new LinkedHashSet<>();
        r.reachable().forEach(m -> owners.add(m.owner()));
        System.out.println("\ndistinct owner classes on the reachable surface = " + owners.size());
        owners.stream().limit(40).forEach(o -> System.out.println("   " + o));
    }

    /**
     * Minimum assertions, so this stops being a test that cannot fail.
     *
     * <p>The measurement above prints to stdout and asserts nothing, which means it silently stops
     * meaning anything the first time Minecraft moves a method — and it was cited in a changelog. It
     * is a discovery aid, not proof (see {@link WorkerReachability} for exactly where it truncates),
     * but it can at least be required to have actually run.
     */
    @org.junit.jupiter.api.Test
    void theAnalysisActuallyRanAndFoundNoAttributeHazard() throws Exception {
        WorkerReachability reach =
            WorkerReachability.over(minecraftRoots(), List.of("net/minecraft/")).index();
        org.junit.jupiter.api.Assertions.assertTrue(reach.indexedClassCount() > 5000,
            "the index looks empty, so any clean result below would be vacuous: "
                + reach.indexedClassCount());

        List<WorkerReachability.MethodRef> entries = new ArrayList<>();
        for (Class<?> evaluator : SafetyGate.allowlisted()) {
            entries.addAll(reach.allMethodsOf(evaluator.getName().replace('.', '/')));
        }
        org.junit.jupiter.api.Assertions.assertFalse(entries.isEmpty(),
            "no entry points resolved from the allowlist");

        WorkerReachability.Result r = reach.cutting(CUTS).reachableFrom(entries);
        org.junit.jupiter.api.Assertions.assertFalse(r.reachable().isEmpty(),
            "the walk reached nothing, so it proved nothing");
        List<String> attributeHazards = r.hazards().stream()
            .filter(h -> h.sink().owner().endsWith("AttributeInstance"))
            .map(Object::toString).toList();
        org.junit.jupiter.api.Assertions.assertTrue(attributeHazards.isEmpty(),
            "an attribute read-modify-write is reachable from a worker without confinement: "
                + attributeHazards);
    }
}