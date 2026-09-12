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
     * The hook can actually stop a recompute.
     *
     * <p>A throttle that computes the right answer and never cancels is a decoration, and it would
     * pass every other test here. The cancel is what makes the decision have an effect.
     */
    @Test
    void theHookCancelsWhenTheThrottleRefuses() {
        boolean cancels = false;
        for (AbstractInsnNode insn : hook().instructions) {
            if (insn instanceof MethodInsnNode call && "cancel".equals(call.name)
                    && call.owner.endsWith("CallbackInfo")) {
                cancels = true;
            }
        }
        assertTrue(cancels,
            "the hook never cancels, so the throttle decides and nothing happens either way");
    }
}
