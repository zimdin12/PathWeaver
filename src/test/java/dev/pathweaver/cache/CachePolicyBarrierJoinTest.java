package dev.pathweaver.cache;

import dev.pathweaver.async.RequestKey;
import dev.pathweaver.async.RequestTarget;
import dev.pathweaver.config.PathCacheMode;
import dev.pathweaver.config.PathWeaverConfig;
import dev.pathweaver.mixin.ServerLevelBlockChangeMixin;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The cache and the switches that silence its observations, tested together.
 *
 * <p>The defect: the block-change hook stops recording while the mod or the cache is off, but stored
 * routes and dispatched candidates survive. Store a route, turn the cache off, break a block on that
 * route, turn it back on inside the age limit, and nothing in the cache looks stale, because the one
 * thing that would have marked it stale was not running. Both switches take effect live, so this is
 * reachable from the settings screen without restarting anything.
 *
 * <p>{@code PathCacheTest} cannot see it. Every test there calls {@code noteBlockChange} itself, so
 * the world is always observed and the blind period never exists. This file is deliberately the join:
 * real configuration publications, the real generation counter, the real cache, and the recording
 * gate the hook consults.
 *
 * <p>The gap is never simulated by asserting that the hook was skipped. It is created by publishing a
 * configuration and then asserting, from the same predicate the hook calls, that recording really is
 * off. A test that merely declined to call {@code noteBlockChange} would prove nothing about whether
 * the hook would have called it.
 */
class CachePolicyBarrierJoinTest {

    private static final Object DIMENSION = "overworld";
    private static final long X = Double.doubleToLongBits(10.5);
    private static final long Y = Double.doubleToLongBits(64.0);
    private static final long Z = Double.doubleToLongBits(10.5);
    /** A block on the stored route, in a section that route depends on. */
    private static final BlockPos ON_THE_ROUTE = new BlockPos(12, 64, 10);

    private PathCache cache;

    @BeforeEach
    void serveMode() {
        cache = new PathCache(64);
        publish(true, PathCacheMode.SERVE);
    }

    @AfterEach
    void restoreDefaults() {
        PathWeaverConfig.set(new PathWeaverConfig());
    }

    // ---------------------------------------------------------------- the join, in both directions

    /**
     * The positive control the rest of the file rests on.
     *
     * <p>Without it, every "was not served" assertion below would also pass against a cache that
     * never serves anything, which is exactly the shape of wrong zero this project keeps meeting.
     */
    @Test
    void aStoredRouteIsServedWhenNoSettingChanged() {
        store(100L);
        assertTrue(serves(110L), "the cache served nothing at all; no assertion below means anything");
    }

    /**
     * The reported defect, on the cache switch. The block change during the gap is invisible by
     * construction: recording is off, which this asserts rather than assumes.
     */
    @Test
    void aRouteStoredBeforeTheCacheWasTurnedOffIsNotServedAfterItComesBack() {
        store(100L);
        assertTrue(serves(105L), "control: the route was servable before the gap");

        publish(true, PathCacheMode.OFF);
        assertFalse(PathWeaverConfig.get().recordsBlockChanges(),
            "the gap does not exist: the hook would still have recorded this change");
        // The block breaks here. Nothing observes it.
        publish(true, PathCacheMode.SERVE);

        assertFalse(serves(120L),
            "a route stored before the cache was switched off was served across an unwatched change");
    }

    /** The same hole, on the master switch. Patching only one of the two switches leaves this open. */
    @Test
    void aRouteStoredBeforeTheModWasTurnedOffIsNotServedAfterItComesBack() {
        store(100L);
        assertTrue(serves(105L), "control: the route was servable before the gap");

        publish(false, PathCacheMode.SERVE);
        assertFalse(PathWeaverConfig.get().recordsBlockChanges(),
            "the gap does not exist: the hook would still have recorded this change");
        publish(true, PathCacheMode.SERVE);

        assertFalse(serves(120L),
            "a route stored before the mod was switched off was served across an unwatched change");
    }

    /**
     * The in-flight case, which invalidating stored entries alone does not close.
     *
     * <p>A search dispatched before the gap is drained after it. Its candidate was recorded under the
     * old policy and describes a world nobody watched since; completing it would put that observation
     * into a cache that had just been emptied, which looks fresh and is not.
     */
    @Test
    void aSearchDispatchedBeforeTheGapDoesNotPopulateTheCacheWhenItLandsAfterIt() {
        cache.remember(request(1L), key(), 100L, X, Y, Z, generation());

        publish(true, PathCacheMode.OFF);
        publish(true, PathCacheMode.SERVE);

        cache.completed(request(1L), straightPath(5), true, generation());
        assertEquals(0, cache.size(), "a candidate from before the gap was stored after it");
        assertFalse(serves(120L), "a route from before the gap was served after it");
    }

    /** The negative control for the case above: with no gap, the same late drain does store. */
    @Test
    void aSearchDrainedLateWithNoSettingChangeStillPopulatesTheCache() {
        cache.remember(request(1L), key(), 100L, X, Y, Z, generation());
        cache.completed(request(1L), straightPath(5), true, generation());
        assertEquals(1, cache.size(),
            "a late drain stores nothing even with no policy change, so the test above is vacuous");
        assertTrue(serves(110L));
    }

    /**
     * The barrier empties the cache once per configuration, not on every call.
     *
     * <p>A barrier that cleared unconditionally would satisfy every assertion above and leave the
     * feature permanently dead, reporting a stream of misses no operator could explain.
     */
    @Test
    void theCacheWorksNormallyOnceTheNewConfigurationIsInForce() {
        store(100L);
        publish(true, PathCacheMode.OFF);
        publish(true, PathCacheMode.SERVE);

        store(200L);
        assertTrue(serves(210L), "nothing stored after the transition could be served either");
        assertEquals(1, cache.size());
    }

    /**
     * Observations taken before the gap are kept, and still withdraw a route stored after it.
     *
     * <p>The barrier deliberately does not clear the section clock. A route stored after the gap
     * carries a dispatch tick after the gap, so an older record cannot wrongly withdraw it, while a
     * record made after that dispatch still must. This pins the second half of that.
     */
    @Test
    void terrainInvalidationStillWorksAfterATransition() {
        publish(true, PathCacheMode.OFF);
        publish(true, PathCacheMode.SERVE);

        store(200L);
        cache.noteBlockChange(DIMENSION.hashCode(), SectionPos.asLong(ON_THE_ROUTE), 205L);
        assertFalse(serves(210L), "the section clock stopped working across a configuration change");
    }

    // -------------------------------------------------------- the gate the hook actually consults

    /** Both switches silence recording, and neither of them alone is the whole rule. */
    @Test
    void recordingStopsWhenEitherSwitchIsOff() {
        assertTrue(recording(true, PathCacheMode.SERVE), "recording is off with everything on");
        assertTrue(recording(true, PathCacheMode.SHADOW),
            "shadow mode measures, so it must keep watching the world");
        assertFalse(recording(true, PathCacheMode.OFF), "the cache switch does not stop recording");
        assertFalse(recording(false, PathCacheMode.SERVE), "the master switch does not stop recording");
        assertFalse(recording(false, PathCacheMode.OFF), "neither switch stops recording");
    }

    /**
     * The hook asks that one predicate and decides nothing for itself.
     *
     * <p>Two copies of this rule is how the switches drift apart, and a drift would not show up in
     * any behavioural test here, because these tests read the predicate rather than the hook. So this
     * reads the bytecode of the hook: it must call {@code recordsBlockChanges}, and must not call
     * {@code resultCacheActive} on its own.
     *
     * <p>The class resource is the right artifact. A mixin is applied to its target, but this class is
     * itself the definition being applied, so what is on disk is what runs.
     */
    @Test
    void theBlockChangeHookGatesOnThatPredicateAndNothingElse() {
        MethodNode hook = hookMethod();
        List<String> calls = new ArrayList<>();
        for (var insn : hook.instructions) {
            if (insn instanceof MethodInsnNode call) calls.add(call.owner + "." + call.name);
        }

        assertEquals(1, calls.stream()
                .filter("dev/pathweaver/config/PathWeaverConfig.recordsBlockChanges"::equals).count(),
            "the hook does not gate on the shared predicate: " + calls);
        assertFalse(calls.contains("dev/pathweaver/config/PathWeaverConfig.resultCacheActive"),
            "the hook re-implements half the recording rule: " + calls);
        // The positive control for the two assertions above: this scan can see calls at all, and it
        // is looking at the method that really does the recording.
        assertTrue(calls.contains("dev/pathweaver/cache/PathCache.noteBlockChange"),
            "this is not the recording hook, so gating assertions about it prove nothing: " + calls);
    }

    // ----------------------------------------------------------------------------------- fixtures

    private static boolean recording(boolean enabled, PathCacheMode mode) {
        publish(enabled, mode);
        return PathWeaverConfig.get().recordsBlockChanges();
    }

    /** Publishes through the real entry point, which is what moves the generation. */
    private static void publish(boolean enabled, PathCacheMode mode) {
        PathWeaverConfig config = new PathWeaverConfig();
        config.enabled = enabled;
        config.resultCacheMode = mode;
        PathWeaverConfig.set(config);
    }

    private static long generation() { return PathWeaverConfig.policyGeneration(); }

    private void store(long tick) {
        cache.remember(request(1L), key(), tick, X, Y, Z, generation());
        cache.completed(request(1L), straightPath(5), true, generation());
    }

    /** A lookup with the settings a dispatch would really have read, not chosen constants. */
    private boolean serves(long tick) {
        PathWeaverConfig config = PathWeaverConfig.get();
        return cache.lookup(key(), X, Y, Z, tick, config.resultCacheMaxAgeTicks,
            config.resultCacheServes(), generation()).isServed();
    }

    private static MethodNode hookMethod() {
        ClassNode node = new ClassNode();
        try (InputStream in = ServerLevelBlockChangeMixin.class
                .getResourceAsStream("ServerLevelBlockChangeMixin.class")) {
            assertNotNull(in, "the block-change mixin class is not on the test classpath");
            new ClassReader(in).accept(node, 0);
        } catch (Exception e) {
            throw new AssertionError("could not read the block-change mixin", e);
        }
        for (MethodNode method : node.methods) {
            if (method.name.startsWith("pathweaver$noteBlockChange")) return method;
        }
        throw new AssertionError("the block-change injection is gone; the cache observes nothing");
    }

    private static PathCacheKey key() {
        return new PathCacheKey(DIMENSION, 10, 64, 10,
            RequestTarget.of(Set.of(new BlockPos(20, 64, 10)), 8, false, 1, 16.0f),
            String.class, Integer.class, 0, 4096, Float.floatToIntBits(1.0f),
            Float.floatToIntBits(0.6f), 3, Float.floatToIntBits(0.6f),
            Float.floatToIntBits(1.95f), 0, new int[] {1});
    }

    private static Path straightPath(int length) {
        List<Node> nodes = new ArrayList<>();
        for (int i = 0; i < length; i++) nodes.add(new Node(10 + i, 64, 10));
        return new Path(nodes, new BlockPos(10 + length - 1, 64, 10), true);
    }

    private static RequestKey request(long token) { return new RequestKey(1L, token, 7); }
}
