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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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

    private static final String GATE_OWNER = "dev/pathweaver/config/PathWeaverConfig";
    private static final String CACHE_OWNER = "dev/pathweaver/cache/PathCache";
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
     *
     * <p>Both switches, for the same reason the stored case tests both: a fix applied to one of them
     * leaves the other open, and the two are separate settings on the same screen.
     */
    @ParameterizedTest(name = "in-flight across {0}")
    @MethodSource("bothSwitches")
    void aSearchDispatchedBeforeTheGapDoesNotPopulateTheCacheWhenItLandsAfterIt(
            String name, Runnable openTheGap) {
        cache.remember(request(1L), key(), 100L, X, Y, Z, generation());

        openTheGap.run();
        assertFalse(PathWeaverConfig.get().recordsBlockChanges(),
            "the gap does not exist: the hook would still have recorded this change");
        publish(true, PathCacheMode.SERVE);

        cache.completed(request(1L), straightPath(5), true, generation());
        assertEquals(0, cache.size(), "a candidate from before the gap was stored after it");
        assertFalse(serves(120L), "a route from before the gap was served after it");
    }

    /** The two live settings that stop the world being watched, each opening the gap on its own. */
    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> bothSwitches() {
        return java.util.stream.Stream.of(
            org.junit.jupiter.params.provider.Arguments.of("the cache switch",
                (Runnable) () -> publish(true, PathCacheMode.OFF)),
            org.junit.jupiter.params.provider.Arguments.of("the master switch",
                (Runnable) () -> publish(false, PathCacheMode.SERVE)));
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
     * When the predicate says no, the hook records nothing. Proved on the control flow, not the call.
     *
     * <p>The first version of this only asserted that the hook CALLS {@code recordsBlockChanges}. That
     * admits calling it and throwing the answer away, and it admits inverting it, both of which would
     * leave every behavioural test in this file green while the hook recorded through the gap or
     * stopped recording at all. The check is now what its name claims: walk the method from the branch
     * the FALSE answer leads to, and {@code noteBlockChange} must not be reachable from there.
     *
     * <p>The class resource is the right artifact. A mixin is applied to its target, but this class is
     * itself the definition being applied, so what is on disk is what runs.
     */
    @Test
    void whenTheGateSaysNoTheHookCannotReachTheRecordingCall() {
        MethodNode hook = hookMethod();
        InsnList code = hook.instructions;

        int gate = indexOfCall(code, GATE_OWNER, "recordsBlockChanges");
        assertTrue(gate >= 0, "the hook does not consult the recording gate at all");
        assertEquals(gate, lastIndexOfCall(code, GATE_OWNER, "recordsBlockChanges"),
            "the hook asks the gate more than once, so which answer controls it is ambiguous");

        AbstractInsnNode next = nextRealInstruction(code.get(gate));
        assertInstanceOf(JumpInsnNode.class, next,
            "the gate answer is not consumed by a branch, so the hook can ignore it: " + next);
        JumpInsnNode branch = (JumpInsnNode) next;
        assertTrue(branch.getOpcode() == Opcodes.IFEQ || branch.getOpcode() == Opcodes.IFNE,
            "the gate answer feeds a branch this test cannot read: opcode " + branch.getOpcode());

        // IFNE jumps when the gate said true, so false falls through. IFEQ is the other way round.
        AbstractInsnNode whenGateSaysNo = branch.getOpcode() == Opcodes.IFNE
            ? branch.getNext() : branch.label;
        assertFalse(reaches(code, whenGateSaysNo, CACHE_OWNER, "noteBlockChange"),
            "with recording off, the hook still reaches noteBlockChange");

        // The positive control. Without it this passes on a hook that records under no condition at
        // all, which is the same silence and the opposite bug.
        AbstractInsnNode whenGateSaysYes = branch.getOpcode() == Opcodes.IFNE
            ? branch.label : branch.getNext();
        assertTrue(reaches(code, whenGateSaysYes, CACHE_OWNER, "noteBlockChange"),
            "the hook never records on any path, so the assertion above proves nothing");
    }

    /**
     * The generation a caller passes is the live one, read at the call.
     *
     * <p>The barrier only fires when the number changes, so a caller passing a constant, a cached
     * field or a stale local would disable it completely and every behavioural test in this file
     * would still pass: they call the cache directly and supply the generation themselves. This binds
     * the three production call sites instead. The generation is the last parameter of each, so the
     * instruction that produced it is the one immediately before the call.
     */
    @Test
    void everyProductionCallerReadsTheGenerationAtTheCall() {
        assertGenerationIsReadAtTheCall(dev.pathweaver.mixin.PathNavigationMixin.class, "lookup");
        assertGenerationIsReadAtTheCall(dev.pathweaver.mixin.PathNavigationMixin.class, "remember");
        assertGenerationIsReadAtTheCall(dev.pathweaver.async.ResultInstaller.class, "completed");
    }

    private static void assertGenerationIsReadAtTheCall(Class<?> caller, String name) {
        int found = 0;
        for (MethodNode method : classNodeOf(caller).methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (!(insn instanceof MethodInsnNode call)) continue;
                if (!CACHE_OWNER.equals(call.owner) || !name.equals(call.name)) continue;
                found++;
                AbstractInsnNode previous = previousRealInstruction(insn);
                assertInstanceOf(MethodInsnNode.class, previous,
                    caller.getSimpleName() + "." + method.name + " does not read the generation at "
                        + "the " + name + " call: " + previous);
                MethodInsnNode source = (MethodInsnNode) previous;
                assertEquals(GATE_OWNER + ".policyGeneration", source.owner + "." + source.name,
                    caller.getSimpleName() + "." + method.name + " passes something other than the "
                        + "live generation to " + name);
            }
        }
        // Positive control: a scan that found no call site would pass every assertion above.
        assertTrue(found > 0, caller.getSimpleName() + " no longer calls " + name
            + ", so this test constrains nothing");
    }

    // ---------------------------------------------------------------- reading the compiled method

    /** Is {@code owner.name} reachable from {@code from}, following both sides of every branch? */
    private static boolean reaches(InsnList code, AbstractInsnNode from, String owner, String name) {
        java.util.Deque<AbstractInsnNode> pending = new java.util.ArrayDeque<>();
        java.util.Set<AbstractInsnNode> seen = new java.util.HashSet<>();
        pending.add(from);
        while (!pending.isEmpty()) {
            AbstractInsnNode insn = pending.poll();
            if (insn == null || !seen.add(insn)) continue;
            if (insn instanceof MethodInsnNode call
                    && owner.equals(call.owner) && name.equals(call.name)) {
                return true;
            }
            int opcode = insn.getOpcode();
            if ((opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN) || opcode == Opcodes.ATHROW) {
                continue;   // this path ends without recording anything
            }
            if (insn instanceof JumpInsnNode jump) {
                pending.add(jump.label);
                if (opcode != Opcodes.GOTO) pending.add(jump.getNext());
                continue;
            }
            pending.add(insn.getNext());
        }
        return false;
    }

    private static int indexOfCall(InsnList code, String owner, String name) {
        for (int i = 0; i < code.size(); i++) {
            if (code.get(i) instanceof MethodInsnNode call
                    && owner.equals(call.owner) && name.equals(call.name)) return i;
        }
        return -1;
    }

    private static int lastIndexOfCall(InsnList code, String owner, String name) {
        int last = -1;
        for (int i = 0; i < code.size(); i++) {
            if (code.get(i) instanceof MethodInsnNode call
                    && owner.equals(call.owner) && name.equals(call.name)) last = i;
        }
        return last;
    }

    /** Skips labels, line numbers and frames, which carry no execution. */
    private static AbstractInsnNode nextRealInstruction(AbstractInsnNode from) {
        AbstractInsnNode insn = from.getNext();
        while (insn != null && insn.getOpcode() < 0) insn = insn.getNext();
        return insn;
    }

    private static AbstractInsnNode previousRealInstruction(AbstractInsnNode from) {
        AbstractInsnNode insn = from.getPrevious();
        while (insn != null && insn.getOpcode() < 0) insn = insn.getPrevious();
        return insn;
    }

    private static ClassNode classNodeOf(Class<?> type) {
        ClassNode node = new ClassNode();
        String resource = type.getName().substring(type.getPackageName().length() + 1) + ".class";
        try (InputStream in = type.getResourceAsStream(resource)) {
            assertNotNull(in, type.getName() + " is not on the test classpath");
            new ClassReader(in).accept(node, 0);
        } catch (java.io.IOException failure) {
            throw new AssertionError("could not read " + type.getName(), failure);
        }
        return node;
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
        for (MethodNode method : classNodeOf(ServerLevelBlockChangeMixin.class).methods) {
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
