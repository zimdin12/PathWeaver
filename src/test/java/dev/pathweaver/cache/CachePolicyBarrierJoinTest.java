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
     * The join, executed rather than read: the real recording decision, across a real transition.
     *
     * <p>This is the whole defect in one test. Store a route. Turn the cache off. Offer the observer
     * a block change on that route, exactly as the hook would on every block update, and it records
     * nothing, because that is what the production function decides. Turn the cache back on inside
     * the route's age limit. Without the barrier the route is still there and still looks fresh,
     * because the one thing that would have marked it stale never ran.
     *
     * <p>Earlier versions of this asserted the hook's bytecode instead. That was worth keeping as a
     * structural check and was never the join: reading a method is not running it.
     */
    @Test
    void aChangeMadeWhileRecordingIsOffIsNotRecorded_andTheRouteDoesNotSurviveIt() {
        store(100L);
        assertTrue(serves(105L), "control: the route was servable before the gap");

        publish(true, PathCacheMode.OFF);
        observe(110L);          // the hook fires; the production decision drops it
        publish(true, PathCacheMode.SERVE);

        assertFalse(serves(115L),
            "a route was served across a change the observer was switched off for");
    }

    /**
     * The positive control for the test above, and the post-transition recording the review asked
     * for: once the switches are back on, the observer really does record again.
     *
     * <p>Without this, the assertion above passes just as well against an observer that has stopped
     * recording permanently, which is the same silence and a worse bug.
     */
    @Test
    void theObserverRecordsAgainAfterTheTransition() {
        publish(true, PathCacheMode.OFF);
        publish(true, PathCacheMode.SERVE);

        store(200L);
        assertTrue(serves(205L), "nothing could be stored or served after the transition at all");

        observe(206L);
        assertFalse(serves(207L),
            "a change made after the transition was not recorded, so the route survived it");
    }

    /** And with no change offered at all, the same post-transition route stays servable. */
    @Test
    void aRouteStoredAfterTheTransitionSurvivesWhenNothingChanges() {
        publish(true, PathCacheMode.OFF);
        publish(true, PathCacheMode.SERVE);

        store(200L);
        assertTrue(serves(207L),
            "the route was withdrawn without any block change, so the test above proves nothing");
    }

    /**
     * Both switches silence the observer, and each of them alone is enough.
     *
     * <p>No configuration is published inside a case, deliberately. Publishing moves the generation
     * and the barrier then empties the cache, which would hide the very thing being measured: this
     * asks whether the CHANGE was recorded, and a cleared cache answers "no route" for a different
     * reason. So each case settles the switches first and then stores, observes and looks up under
     * one policy.
     */
    @Test
    void theObserverRecordsNothingWhileEitherSwitchIsOff() {
        for (Object[] silenced : new Object[][] {
                {"the cache switch", true, PathCacheMode.OFF},
                {"the master switch", false, PathCacheMode.SERVE},
                {"both", false, PathCacheMode.OFF}}) {
            publish((Boolean) silenced[1], (PathCacheMode) silenced[2]);
            cache = new PathCache(64);
            store(100L);
            observe(105L);

            assertTrue(servedIgnoringMode(110L),
                silenced[0] + " did not stop the observer: the change was recorded anyway");
        }
    }

    /**
     * The negative control for the case above, in the same shape.
     *
     * <p>Every assertion there is that a route SURVIVED, which is also what a broken observer that
     * records nothing under any settings would produce. This is the same sequence with both switches
     * on, where the change must be recorded and the route must not survive.
     */
    @Test
    void theSameChangeIsRecordedWithBothSwitchesOn() {
        publish(true, PathCacheMode.SERVE);
        cache = new PathCache(64);
        store(100L);
        observe(105L);

        assertFalse(servedIgnoringMode(110L),
            "with everything on, the observer still recorded nothing; it can never say yes");
    }

    /**
     * The hook is an adapter and holds no rule of its own.
     *
     * <p>Kept as a structural check beside the executed ones, because the tests above run the
     * observer directly and a hook that stopped calling it, or that decided something for itself,
     * would leave them all green. A branch in this method is the drift this is watching for: it must
     * call the observer exactly once and contain no conditional jump.
     *
     * <p>The class resource is the right artifact. A mixin is applied to its target, but this class
     * is itself the definition being applied, so what is on disk is what runs.
     */
    @Test
    void theBlockChangeHookDelegatesTheWholeDecision() {
        MethodNode hook = hookMethod();
        List<String> calls = new ArrayList<>();
        List<String> branches = new ArrayList<>();
        for (AbstractInsnNode insn : hook.instructions) {
            if (insn instanceof MethodInsnNode call) calls.add(call.owner + "." + call.name);
            if (insn instanceof JumpInsnNode jump && jump.getOpcode() != Opcodes.GOTO) {
                branches.add(String.valueOf(jump.getOpcode()));
            }
        }

        assertEquals(1, calls.stream()
                .filter((CACHE_OWNER.replace("PathCache", "BlockChangeObserver") + ".observe")::equals)
                .count(),
            "the hook does not hand the decision to the observer exactly once: " + calls);
        assertEquals(List.of(), branches,
            "the hook branches, so it is deciding something the observer is supposed to own");
        assertFalse(calls.contains(GATE_OWNER + ".recordsBlockChanges"),
            "the hook re-implements the recording rule instead of delegating it: " + calls);
    }

    /**
     * The generation a caller passes is the live one, read at the call.
     *
     * <p>The barrier only fires when the number changes, so a caller passing a constant, a cached
     * field or a stale local would disable it completely and every behavioural test in this file
     * would still pass: they call the cache directly and supply the generation themselves. This binds
     * the three production call sites instead. The generation is the last parameter of each, so the
     * instruction that produced it is the one immediately before the call.
     *
     * <p>It must come from {@code PathWeaverConfig.generation()}, the instance method, so the number
     * and the settings come off ONE published object. A static read of a counter would be two reads
     * that a publication can interleave, and one of the two orders leaves the barrier silent while
     * the cache acts on settings it has not seen.
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
                assertEquals(GATE_OWNER + ".generation", source.owner + "." + source.name,
                    caller.getSimpleName() + "." + method.name + " passes something other than the "
                        + "published config's own generation to " + name);
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

    private static long generation() { return PathWeaverConfig.get().generation(); }

    /**
     * One block update on the stored route, through the production decision.
     *
     * <p>Exactly what {@code ServerLevelBlockChangeMixin} calls, with the arguments it computes from
     * the level. The gap is never faked by declining to call this: the call always happens, and what
     * differs is what the observer decides to do with it.
     */
    private void observe(long tick) {
        BlockChangeObserver.observe(PathWeaverConfig.get(), true, cache,
            DIMENSION.hashCode(), SectionPos.asLong(ON_THE_ROUTE), tick);
    }

    private void store(long tick) {
        cache.remember(request(1L), key(), tick, X, Y, Z, generation());
        cache.completed(request(1L), straightPath(5), true, generation());
    }

    /**
     * A lookup that serves regardless of the mode, for asking whether a CHANGE was recorded.
     *
     * <p>{@link #serves} reads {@code resultCacheServes()}, so under a switched-off cache it returns
     * false whatever the clock says, and that answer cannot distinguish "the change was recorded"
     * from "this mode does not serve". The generation is unchanged from the store, so the barrier
     * does not fire here either.
     */
    private boolean servedIgnoringMode(long tick) {
        return cache.lookup(key(), X, Y, Z, tick, PathWeaverConfig.get().resultCacheMaxAgeTicks,
            true, generation()).isServed();
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
