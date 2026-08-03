package dev.pathweaver.mixin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.pathweaver.gate.SafetyGate;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Every admitted evaluator that reads live mob state from inside a search must have a mixin covering
 * the class that actually declares the call.
 *
 * <p>This exists because 0.5.0 shipped with the hazard half-fixed. {@code WalkNodeEvaluatorMixin}
 * redirects {@code Mob.maxUpStep()} in {@code WalkNodeEvaluator} and its javadoc declares the
 * attribute race eliminated. {@code AmphibiousNodeEvaluator} overrides {@code getNeighbors} and
 * makes its <em>own</em> {@code maxUpStep()} call, and a mixin transforms only its target class's
 * bytecode — so axolotls, turtles, drowned and frogs kept racing. {@code require = 1} passed on
 * {@code WalkNodeEvaluator} and said nothing about the subclass, which is precisely why a
 * per-mixin assertion could not catch it and this one can.
 *
 * <p>The rule is therefore stated over the <em>allowlist</em> rather than over the mixins: for every
 * class the gate admits, find which live-mob reads it declares itself, and require that PathWeaver
 * has a mixin for that exact class. Add a seventh evaluator, or let Mojang move a call into a
 * subclass, and this fails rather than going quiet.
 */
class LiveMobReadCoverageContractTest {

    /**
     * Live mob reads that must never run on a worker.
     *
     * <p>{@code maxUpStep} is {@code AttributeInstance.getValue()}, a read-modify-write over plain
     * non-volatile fields. {@code getRandom} advances a {@code RandomSource} the server thread also
     * uses. Both are handled by handing the worker a confined value instead of the live call.
     */
    private static final Set<String> CONFINED_MOB_READS = Set.of("maxUpStep", "getRandom");

    /** Evaluator classes PathWeaver ships a mixin for. */
    private static final Set<String> MIXED_IN = Set.of(
        "net.minecraft.world.level.pathfinder.WalkNodeEvaluator",
        "net.minecraft.world.level.pathfinder.FlyNodeEvaluator",
        "net.minecraft.world.level.pathfinder.AmphibiousNodeEvaluator");

    @Test
    void everyAdmittedEvaluatorDeclaringALiveMobReadHasAMixinForThatExactClass() throws IOException {
        Map<String, Set<String>> declaredReads = new LinkedHashMap<>();
        for (Class<?> evaluator : SafetyGate.allowlisted()) {
            // Walk the hierarchy: a subclass inherits its parent's covered calls, but any call it
            // DECLARES itself lives in its own bytecode and needs its own mixin.
            for (Class<?> level = evaluator;
                    level != null && level.getName().startsWith("net.minecraft");
                    level = level.getSuperclass()) {
                Set<String> reads = liveMobReadsDeclaredBy(level);
                if (!reads.isEmpty()) declaredReads.put(level.getName(), reads);
            }
        }

        assertFalse(declaredReads.isEmpty(),
            "found no live mob reads at all, which means this test stopped looking rather than the "
                + "hazard stopped existing");

        Set<String> needMixin = new TreeSet<>(declaredReads.keySet());
        Set<String> haveMixin = new TreeSet<>(MIXED_IN);
        assertEquals(haveMixin, needMixin,
            "every class DECLARING a confined live-mob read needs its own mixin — a @Redirect only "
                + "transforms its target class, so an override in a subclass is a different method "
                + "that happens to share a name. Declared reads: " + declaredReads);
    }

    @Test
    void theAmphibiousEvaluatorReallyDoesDeclareItsOwnStepHeightRead() throws IOException {
        // Non-vacuity for the case that shipped broken. If Mojang ever removes this call the test
        // above would still pass while silently covering nothing, so pin the specific fact.
        Set<String> reads = liveMobReadsDeclaredBy(
            net.minecraft.world.level.pathfinder.AmphibiousNodeEvaluator.class);
        assertTrue(reads.contains("maxUpStep"),
            "AmphibiousNodeEvaluator is expected to declare its own Mob.maxUpStep() call; if that is "
                + "no longer true the coverage rule above needs rechecking, not relaxing");
    }

    /** Names from {@code CONFINED_MOB_READS} invoked on {@code Mob} by this class's own bytecode. */
    private static Set<String> liveMobReadsDeclaredBy(Class<?> type) throws IOException {
        Set<String> found = new LinkedHashSet<>();
        new ClassReader(classBytes(type)).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String called,
                                                String calledDescriptor, boolean isInterface) {
                        if (owner.equals("net/minecraft/world/entity/Mob")
                                && CONFINED_MOB_READS.contains(called)) {
                            found.add(called);
                        }
                    }
                };
            }
        }, ClassReader.SKIP_FRAMES);
        return found;
    }

    private static byte[] classBytes(Class<?> type) throws IOException {
        String resource = "/" + type.getName().replace('.', '/') + ".class";
        try (InputStream in = type.getResourceAsStream(resource)) {
            if (in == null) throw new IOException("missing class resource " + resource);
            return in.readAllBytes();
        }
    }
}
