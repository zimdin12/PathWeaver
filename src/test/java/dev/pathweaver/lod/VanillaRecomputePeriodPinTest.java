package dev.pathweaver.lod;

import net.minecraft.world.entity.ai.navigation.PathNavigation;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RecomputeThrottle#VANILLA_RECOMPUTE_PERIOD_TICKS} is read out of vanilla, so this checks it
 * against vanilla rather than against the last person to type it.
 *
 * <p>That constant is load-bearing twice over: it is the floor {@code lodIntervalTicks} is clamped to,
 * and it is the number the project page uses to say what LOD is worth. Both are wrong the moment
 * Mojang changes the guard inside {@code recomputePath}, and nothing else in the suite would notice,
 * because every other LOD test drives our own arithmetic with numbers we chose.
 *
 * <p>The relevant vanilla code is:
 *
 * <pre>
 *   if (level.getGameTime() - timeLastRecompute &gt; 20 &amp;&amp; canUpdatePath()) { ...createPath... }
 *   else { hasDelayedRecomputation = true; }
 * </pre>
 *
 * <p>So the soonest a second real search can follow the first is the twenty-first tick, and the
 * derived period is the literal plus one.
 */
class VanillaRecomputePeriodPinTest {

    private static MethodNode recomputePath() {
        ClassNode node = new ClassNode();
        String resource = "/" + PathNavigation.class.getName().replace('.', '/') + ".class";
        try (InputStream in = PathNavigation.class.getResourceAsStream(resource)) {
            assertNotNull(in, "vanilla PathNavigation is not on the test classpath, so this test "
                + "cannot check anything and must not pass quietly");
            new ClassReader(in).accept(node, 0);
        } catch (IOException failure) {
            throw new AssertionError("could not read vanilla PathNavigation", failure);
        }
        for (MethodNode method : node.methods) {
            if ("recomputePath".equals(method.name) && "()V".equals(method.desc)) return method;
        }
        throw new AssertionError("vanilla PathNavigation has no recomputePath()V; the method the LOD "
            + "hook cancels has been renamed or removed");
    }

    /**
     * The positive control. If the method were found but empty, or found and not the one that
     * searches, every assertion below would be about the wrong code.
     */
    @Test
    void theMethodFoundIsTheOneThatRunsAPathSearch() {
        List<String> calls = new ArrayList<>();
        for (AbstractInsnNode insn : recomputePath().instructions) {
            if (insn instanceof MethodInsnNode call) calls.add(call.name);
        }
        assertTrue(calls.contains("createPath"),
            "the vanilla method being pinned never calls createPath, so it is not the one that "
                + "recomputes a route: " + calls);
        assertTrue(calls.contains("getGameTime"),
            "the vanilla method being pinned never reads the game clock, so it cannot be the one "
                + "holding the refresh floor: " + calls);
    }

    /**
     * The pin proper: vanilla still refuses to search twice inside its own window, and the window is
     * still the number our constant was derived from.
     */
    @Test
    void vanillaStillHoldsTheRefreshFloorOurConstantWasDerivedFrom() {
        List<Long> longLiterals = new ArrayList<>();
        boolean comparesLongsAndBranches = false;
        AbstractInsnNode previous = null;
        for (AbstractInsnNode insn : recomputePath().instructions) {
            if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof Long literal) {
                longLiterals.add(literal);
            }
            if (insn.getOpcode() == Opcodes.IFLE && previous != null
                    && previous.getOpcode() == Opcodes.LCMP) {
                comparesLongsAndBranches = true;
            }
            if (insn.getOpcode() != -1) previous = insn;
        }
        assertTrue(comparesLongsAndBranches,
            "vanilla no longer compares two longs and branches on the result inside recomputePath, "
                + "so the elapsed-time guard our floor is derived from is gone");
        assertEquals(List.of((long) RecomputeThrottle.VANILLA_RECOMPUTE_PERIOD_TICKS - 1),
            longLiterals,
            "vanilla's recompute window is no longer "
                + (RecomputeThrottle.VANILLA_RECOMPUTE_PERIOD_TICKS - 1) + " ticks. "
                + "RecomputeThrottle.VANILLA_RECOMPUTE_PERIOD_TICKS, the clamp floor it feeds and "
                + "the figures on the project page are all derived from that number and are now "
                + "wrong. Literals found: " + longLiterals);
    }
}
