package dev.pathweaver.lod;

import dev.pathweaver.mixin.PathNavigationMixin;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The LOD hook is an adapter. The rule lives in {@link RecomputeThrottle} and nowhere else.
 *
 * <p>{@code RecomputeThrottleTest} drives the rule directly, which proves the rule is right and
 * proves nothing about whether the thing in the game uses it. A hook that read
 * {@code config.lodEnabled} itself, or compared the interval itself, would leave every test in that
 * file green while the shipped behaviour came from a second copy of the rule that nothing executes.
 * That is the failure this file exists to catch, and this project has shipped it once already in the
 * block-change gate.
 *
 * <p>Read from the compiled mixin rather than by running it: applying a mixin needs a game, and the
 * class on disk is the definition that gets applied, so what is checked here is what runs.
 */
class RecomputeThrottleAdapterTest {

    private static final String THROTTLE = "dev/pathweaver/lod/RecomputeThrottle";
    private static final String CONFIG = "dev/pathweaver/config/PathWeaverConfig";
    private static final String HOOK = "pathweaver$throttleDistantRecompute";

    private static ClassNode mixin() {
        ClassNode node = new ClassNode();
        String resource = PathNavigationMixin.class.getSimpleName() + ".class";
        try (InputStream in = PathNavigationMixin.class.getResourceAsStream(resource)) {
            assertNotNull(in, "PathNavigationMixin is not on the test classpath");
            new ClassReader(in).accept(node, 0);
        } catch (java.io.IOException failure) {
            throw new AssertionError("could not read PathNavigationMixin", failure);
        }
        return node;
    }

    private static MethodNode hook() {
        for (MethodNode method : mixin().methods) {
            if (method.name.startsWith(HOOK)) return method;
        }
        throw new AssertionError("the LOD hook is gone; distant mobs are never throttled");
    }

    /** The hook hands the decision over exactly once. */
    @Test
    void theHookAsksTheThrottleAndNothingElseDecides() {
        List<String> calls = new ArrayList<>();
        for (AbstractInsnNode insn : hook().instructions) {
            if (insn instanceof MethodInsnNode call) calls.add(call.owner + "." + call.name);
        }
        assertEquals(1, calls.stream().filter((THROTTLE + ".allows")::equals).count(),
            "the hook does not hand the decision to RecomputeThrottle exactly once: " + calls);
    }

    /**
     * No copy of the rule. The hook must not read a single LOD setting for itself.
     *
     * <p>This is the assertion that would have caught a second implementation. Reading
     * {@code lodEnabled} here to skip the call, or {@code lodIntervalTicks} to compare a tick, is how
     * the shipped behaviour drifts away from the tested behaviour one convenience at a time.
     */
    @Test
    void theHookReadsNoLodSettingOfItsOwn() {
        List<String> reads = new ArrayList<>();
        for (AbstractInsnNode insn : hook().instructions) {
            if (insn instanceof FieldInsnNode field
                    && CONFIG.equals(field.owner) && field.name.startsWith("lod")) {
                reads.add(field.name);
            }
        }
        assertEquals(List.of(), reads,
            "the hook reads LOD settings itself, so the rule now has two implementations: " + reads);
    }

    /**
     * The whole mod has exactly one caller of the throttle.
     *
     * <p>A second call site is how a rule that was reviewed once ends up applied twice with different
     * arguments. If LOD is ever wanted somewhere else, this assertion should be changed deliberately
     * rather than discovered later.
     */
    @Test
    void onlyTheHookCallsTheThrottle() {
        int callers = 0;
        for (MethodNode method : mixin().methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call
                        && THROTTLE.equals(call.owner) && "allows".equals(call.name)) {
                    callers++;
                }
            }
        }
        assertEquals(1, callers, "RecomputeThrottle.allows is called " + callers + " times in the "
            + "navigation mixin; the rule is being applied from more than one place");
    }

    /**
     * The hook acts on the answer, and makes no decision of its own beyond two.
     *
     * <p>An earlier version of this test asserted only that a {@code cancel} call appeared somewhere
     * in the method, and a mutation that wrapped the cancel in {@code if (tick < 0)} sailed through
     * it: the call was still there, it just never fired. The witness caught that, which is what the
     * witness is for, and the test is stronger rather than the mutation being made easier.
     *
     * <p>The property is the number of decisions. This hook is allowed exactly two conditional
     * branches: is this a server level, and what did the throttle say. Any third condition means the
     * hook has started deciding for itself, whether that is a guard around the cancel, a distance
     * compared here, or a settings read. Counting them is crude and it is checkable, which beats an
     * assertion that cannot fail.
     */
    @Test
    void theHookActsOnTheAnswerAndDecidesNothingElse() {
        List<String> branches = new ArrayList<>();
        boolean cancels = false;
        for (AbstractInsnNode insn : hook().instructions) {
            if (insn instanceof org.objectweb.asm.tree.JumpInsnNode jump
                    && jump.getOpcode() != org.objectweb.asm.Opcodes.GOTO) {
                branches.add(String.valueOf(jump.getOpcode()));
            }
            if (insn instanceof MethodInsnNode call && "cancel".equals(call.name)
                    && call.owner.endsWith("CallbackInfo")) {
                cancels = true;
            }
        }
        assertTrue(cancels,
            "the hook never cancels, so the throttle decides and nothing happens either way");
        assertEquals(2, branches.size(),
            "the hook makes " + branches.size() + " decisions; it is allowed two, the server-level "
                + "check and the throttle's answer. A third means the rule has leaked back into it");
    }

    /**
     * A refused refresh is kept pending, the way vanilla keeps its own: true is written into
     * {@code hasDelayedRecomputation} before the call is cancelled.
     *
     * <p>Catches the defect the distance-LOD campaign found. The hook cancelled without the flag, so
     * nothing retried a throttled refresh and a block changed once near a distant mob's route was never
     * acted on. {@code LodDeferralGameTest} proves the behaviour in a running server; this is the cheap
     * standing check that the line doing it is still there and still comes first.
     */
    @Test
    void aRefusedRefreshIsMarkedPendingBeforeItIsCancelled() {
        int flagWrite = -1;
        int cancel = -1;
        int i = 0;
        for (AbstractInsnNode insn : hook().instructions) {
            if (insn instanceof FieldInsnNode field && insn.getOpcode() == org.objectweb.asm.Opcodes.PUTFIELD
                    && "hasDelayedRecomputation".equals(field.name)) {
                AbstractInsnNode value = insn.getPrevious();
                while (value != null && value.getOpcode() == -1) value = value.getPrevious();
                if (value != null && value.getOpcode() == org.objectweb.asm.Opcodes.ICONST_1) flagWrite = i;
            }
            if (insn instanceof MethodInsnNode call && "cancel".equals(call.name)
                    && call.owner.endsWith("CallbackInfo")) {
                cancel = i;
            }
            i++;
        }
        assertTrue(flagWrite >= 0,
            "the hook cancels a refused refresh without marking it pending, so nothing will retry it and "
                + "the block change is dropped rather than delayed");
        assertTrue(flagWrite < cancel,
            "the pending flag is written after the cancel instead of before it");
    }

    /**
     * The hook times the throttle on vanilla's stamp and vanilla's clock, and keeps no counter.
     *
     * <p>This is the assertion that was missing, and its absence is why 0.9.0 shipped a throttle that
     * measured the wrong thing. The first version kept a {@code pathweaver$lastRecomputeTick} field
     * and wrote the current tick into it whenever the throttle said yes. That counts CALLS, and
     * vanilla answers most calls by setting {@code hasDelayedRecomputation} instead of searching, so
     * an interval of N did not mean one search every N ticks. It also swallowed the recompute that
     * {@code pathweaver$rearmRecompute} re-arms after a failed async install, because our counter had
     * already been advanced by the call that dispatched it.
     *
     * <p>Every test in the file passed throughout, because they all check that the hook asks the
     * throttle, not what it asks the throttle ABOUT.
     *
     * <p>The second half matters as much as the first: {@code getGameTime} and the server's
     * {@code getTickCount} are different clocks, and comparing one against a stamp written from the
     * other is meaningless even when both happen to be increasing.
     */
    @Test
    void theHookTimesTheThrottleOnVanillasStampAndClock() {
        List<String> fieldReads = new ArrayList<>();
        List<String> calls = new ArrayList<>();
        for (AbstractInsnNode insn : hook().instructions) {
            if (insn instanceof FieldInsnNode field) fieldReads.add(field.name);
            if (insn instanceof MethodInsnNode call) calls.add(call.name);
        }
        assertTrue(fieldReads.contains("timeLastRecompute"),
            "the hook does not read vanilla's timeLastRecompute, so whatever it is timing the "
                + "interval against is not when vanilla last searched: " + fieldReads);
        assertTrue(calls.contains("getGameTime"),
            "the hook does not read getGameTime, so it is comparing vanilla's stamp against some "
                + "other clock: " + calls);
        assertEquals(List.of(), calls.stream().filter("getTickCount"::equals).toList(),
            "the hook reads the server tick count, which is a different clock from the game time "
                + "vanilla stamps timeLastRecompute with");
        assertEquals(List.of(), fieldReads.stream().filter(f -> f.startsWith("pathweaver$")).toList(),
            "the hook keeps a field of its own to time the interval; vanilla already records when it "
                + "last searched, and a second counter drifts from it: " + fieldReads);
    }
}
