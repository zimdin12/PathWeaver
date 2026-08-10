package dev.pathweaver.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.pathweaver.gate.SafetyGate;
import java.util.List;
import java.util.Set;
import net.minecraft.world.level.pathfinder.SwimNodeEvaluator;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import org.junit.jupiter.api.Test;

/**
 * The status line must describe what the tier did, not what the scan found.
 *
 * <p>Found by running the command on a real 222-mod pack rather than by reading it: at the unsafe
 * tier it reported all six movement families as denied and "running on the server thread", while
 * that same server was dispatching all six and had installed a thousand paths. Every number
 * underneath it was correct; only the sentence a human reads was wrong.
 *
 * <p>That is the second diagnostic in this release to contradict the code it describes, so the rule
 * is pinned here rather than left to care.
 */
class ScanSummaryTest {

    private static final Set<Class<?>> DENIED =
        Set.of(WalkNodeEvaluator.class, SwimNodeEvaluator.class);

    private static String joined(List<String> lines) {
        return String.join(" | ", lines);
    }

    @Test
    void nothingDeniedReadsAsActive() {
        String text = joined(PathWeaverCommand.scanSummary(Set.of(), false));
        assertTrue(text.contains("no movement family is denied"), text);
    }

    @Test
    void deniedAndNotWaivedSaysThoseSearchesAreSynchronous() {
        String text = joined(PathWeaverCommand.scanSummary(DENIED, false));
        assertTrue(text.contains("denied"), text);
        assertTrue(text.contains("run on the server thread"), text);
        assertTrue(text.contains("WalkNodeEvaluator"), text);
    }

    @Test
    void deniedButWaivedMustNotClaimThoseSearchesAreSynchronous() {
        String text = joined(PathWeaverCommand.scanSummary(DENIED, true));
        // The exact sentence that was wrong on a live server.
        assertFalse(text.contains("run on the server thread"),
            "the tier waived these denials, so the searches are running off-thread: " + text);
        assertTrue(text.contains("running anyway"), text);
        assertTrue(text.contains("Unsafe"), text);
        assertTrue(text.contains("WalkNodeEvaluator"),
            "an operator still needs to know which families are being run unchecked: " + text);
    }

    @Test
    void waivingNothingIsStillReportedAsActiveRatherThanAsAWaiver() {
        String text = joined(PathWeaverCommand.scanSummary(Set.of(), true));
        assertTrue(text.contains("no movement family is denied"), text);
        assertFalse(text.contains("running anyway"),
            "with nothing denied there is nothing to waive, and saying otherwise invites the "
                + "operator to think the tier is doing something: " + text);
    }

    /**
     * A failed scan must outrank the tier in the report, because it outranks it in the code.
     *
     * <p>{@code ForeignMixinScanner} only clears the denial set when {@code failed() == 0}, so at the
     * shipped {@code UNSAFE} default with one scan error the true state is "every family
     * synchronous, mod inert". Deriving the summary from the tier alone printed "running anyway,
     * because the tier is Unsafe" — inventing a risk the operator is not taking while concealing that
     * they installed something doing nothing. Both halves are wrong and they point opposite ways.
     */
    @Test
    void aFailedScanIsReportedAsRefusalEvenAtTheWaivingTier() {
        List<String> lines = PathWeaverCommand.scanSummary(
            List.of(WalkNodeEvaluator.class, SwimNodeEvaluator.class), true, true);
        String text = String.join(" | ", lines);
        assertTrue(text.contains("FAILED"), "the scan failure must lead: " + text);
        assertTrue(text.contains("server thread"),
            "the operator must be told the searches are synchronous: " + text);
        assertFalse(text.contains("running anyway"),
            "a failed scan is not waived by any tier, so this must not claim otherwise: " + text);
        assertFalse(text.contains("Keep backups"),
            "do not warn about a risk that is not being taken: " + text);
    }

    /**
     * "Nothing is denied" must not be reported as "everything can run".
     *
     * <p>Dispatch refuses every {@code WalkNodeEvaluator}-derived family — five of the six — while
     * Fabric's land path-type registry is unverified. This line reported all-clear straight through
     * that, while {@code /pathweaver mobs} reported the opposite, so the mod's own diagnostics
     * contradicted each other on the exact question an operator asks first.
     */
    @Test
    void nothingDeniedStillReportsFamiliesThatCannotDispatch() {
        String text = joined(PathWeaverCommand.scanSummary(
            Set.of(), false, false, List.of("WalkNodeEvaluator", "FlyNodeEvaluator")));
        assertTrue(text.contains("cannot dispatch"),
            "a family dispatch would refuse must be reported even when no mod is blamed: " + text);
        assertTrue(text.contains("WalkNodeEvaluator") && text.contains("FlyNodeEvaluator"),
            "the report must name them: " + text);
        assertFalse(text.contains("no movement family is denied"),
            "the all-clear line must not appear alongside families that cannot dispatch: " + text);
    }

    @Test
    void nothingDeniedAndEverythingDispatchableIsStillTheAllClear() {
        String text = joined(PathWeaverCommand.scanSummary(Set.of(), false, false, List.of()));
        assertTrue(text.contains("no movement family is denied"),
            "with nothing denied and nothing undispatchable this must stay the all-clear: " + text);
    }

    /**
     * The production input, which the formatting tests above do not exercise.
     *
     * <p>They pass their own list, so they pin the branch and not the value status actually reports.
     * That is the same shape as the bug this release fixed — a predicate covered on one side and
     * duplicated on the other — so the producer gets pinned to the same predicate the banner uses.
     */
    /**
     * Denying one family must not make its subclasses look like a separate problem.
     *
     * <p>The previous version of this test computed its expectation by calling the method under test,
     * so it passed for any implementation. It also missed the real defect: {@code SafetyGate.isDenied}
     * matches with {@code isAssignableFrom}, so denying {@code WalkNodeEvaluator} refuses all five
     * land-derived families while the denied list names exactly one — and the four inherited refusals
     * were then reported as having "a different reason", followed by a cause that was not the cause.
     */
    @Test
    void familiesRefusedByInheritanceAreNotReportedAsASeparateProblem() {
        List<String> produced = PathWeaverCommand.undispatchableFamilyNames(
            Set.of(net.minecraft.world.level.pathfinder.WalkNodeEvaluator.class));
        assertFalse(produced.contains("FlyNodeEvaluator"),
            "Fly is refused BY the Walk denial, not by a different reason: " + produced);
        assertFalse(produced.contains("AmphibiousNodeEvaluator"),
            "Amphibious is refused BY the Walk denial: " + produced);
        assertFalse(produced.contains("FrogNodeEvaluator"),
            "Frog is refused BY the Walk denial: " + produced);
        assertFalse(produced.contains("HomeNodeEvaluator"),
            "Creaking is refused BY the Walk denial: " + produced);
        assertFalse(produced.contains("WalkNodeEvaluator"),
            "the denied family itself is already reported as denied: " + produced);
    }

    @Test
    void withNothingDeniedTheProducerStillReportsWhatDispatchRefuses() {
        List<String> produced = PathWeaverCommand.undispatchableFamilyNames(Set.of());
        for (Class<?> family : dev.pathweaver.gate.SafetyGate.allowlisted()) {
            if (!dev.pathweaver.gate.SafetyGate.canDispatch(family)) {
                assertTrue(produced.contains(family.getSimpleName()),
                    family.getSimpleName() + " is refused by dispatch and must be reported: "
                        + produced);
            }
        }
    }

    /**
     * The count status reports must equal the count the banner reports. Nothing asserted that.
     *
     * <p>Three rounds fought over this line. Round three named the inherited refusals and invented a
     * cause for them; round four subtracted them and they vanished, so on the DEFAULT AUDITED shape —
     * Fabric API denies exactly {@code WalkNodeEvaluator}, which refuses five families by inheritance
     * — status implied one family refused while the banner said five. A count parity assertion would
     * have caught both versions.
     */
    @Test
    void statusReportsTheSameNumberOfRefusedFamiliesAsTheBanner() {
        Set<Class<?>> denied = Set.of(net.minecraft.world.level.pathfinder.WalkNodeEvaluator.class);
        int bannerRefused = 0;
        for (Class<?> family : dev.pathweaver.gate.SafetyGate.allowlisted()) {
            for (Class<?> d : denied) {
                if (d.isAssignableFrom(family)) { bannerRefused++; break; }
            }
        }
        List<String> inherited = PathWeaverCommand.familiesRefusedByInheritance(denied);
        int statusRefused = denied.size() + inherited.size();
        assertEquals(bannerRefused, statusRefused,
            "status counted " + statusRefused + " refused families, the banner counts "
                + bannerRefused + " — the disagreement this release exists to end. inherited="
                + inherited);
    }

    /** The inherited families must be named, not silently dropped and not given a fabricated cause. */
    @Test
    void inheritedRefusalsAreNamedAndAttributedToTheDenialThatCausesThem() {
        List<String> inherited = PathWeaverCommand.familiesRefusedByInheritance(
            Set.of(net.minecraft.world.level.pathfinder.WalkNodeEvaluator.class));
        assertTrue(inherited.contains("FlyNodeEvaluator") && inherited.contains("FrogNodeEvaluator"),
            "families refused by inheriting from a denied evaluator must be named: " + inherited);
        assertFalse(inherited.contains("SwimNodeEvaluator"),
            "Swim does not inherit from Walk and must not be attributed to it: " + inherited);
        assertFalse(inherited.contains("WalkNodeEvaluator"),
            "the denied family itself is reported as denied, not as inherited: " + inherited);
    }

    /**
     * The under-report direction, which nothing asserted.
     *
     * <p>Every existing case checked that a family was ABSENT. Replacing the closure test with
     * "anything is denied, so subtract everything" therefore survived the whole suite — and that
     * hides families refused for a genuinely different reason behind an unrelated denial.
     */
    @Test
    void aDenialOfOneFamilyDoesNotHideFamiliesRefusedForOtherReasons() {
        // Swim denied. Swim shares no inheritance with the land families, so none of them may be
        // attributed to it -- they must still be reported as refused in their own right.
        List<String> produced = PathWeaverCommand.undispatchableFamilyNames(
            Set.of(net.minecraft.world.level.pathfinder.SwimNodeEvaluator.class));
        for (Class<?> family : dev.pathweaver.gate.SafetyGate.allowlisted()) {
            if (family == net.minecraft.world.level.pathfinder.SwimNodeEvaluator.class) continue;
            if (dev.pathweaver.gate.SafetyGate.canDispatch(family)) continue;
            assertTrue(produced.contains(family.getSimpleName()),
                family.getSimpleName() + " is refused and does not inherit from the denied Swim "
                    + "evaluator, so it must still be reported: " + produced);
        }
    }

    /**
     * The two scan numbers must keep separate names.
     *
     * <p>Measured on a real 221-jar pack at the shipped default, same server and same second: the
     * startup log said {@code deniedFamilies=0} and {@code /pathweaver status} said
     * {@code deniedFamilies=6}. Both were correct — one counts what is ENFORCED after the tier has had
     * its say, the other what the scan FOUND — and an operator reading one label with two meanings has
     * no way to know that. Reverting the fix leaves every other test green, so this pins the labels
     * where they are printed.
     */
    /**
     * Each label must carry the number it names.
     *
     * <p>The bytecode contract below cannot see this: swapping the two sources leaves both labels
     * printed and both sources read, so every assertion in it still passes while
     * {@code /pathweaver status} reports each count under the other's name — on the real pack that
     * inverts to {@code deniedByScan=0, enforced=6} at the shipped default. Only a value can tell,
     * so the two counts are given deliberately different values here.
     */
    @Test
    void eachScanLabelCarriesTheNumberItNames() {
        java.util.Set<Class<?>> saved;
        synchronized (SafetyGate.deniedBySafety) {
            saved = java.util.Set.copyOf(SafetyGate.deniedBySafety);
        }
        try {
            synchronized (SafetyGate.deniedBySafety) {
                SafetyGate.deniedBySafety.clear();
                SafetyGate.deniedBySafety.add(SwimNodeEvaluator.class);
            }
            var decision = new dev.pathweaver.gate.ForeignMixinScanner.ScanDecision(
                java.util.Set.of(net.minecraft.world.level.pathfinder.WalkNodeEvaluator.class,
                    net.minecraft.world.level.pathfinder.FlyNodeEvaluator.class,
                    net.minecraft.world.level.pathfinder.SwimNodeEvaluator.class),
                331, 0, java.util.List.of());
            String line = PathWeaverCommand.ScanCounts.of(decision).line();
            assertTrue(line.contains("deniedByScan=3"),
                "deniedByScan must be what the SCAN found: " + line);
            assertTrue(line.contains("enforced=1"),
                "enforced must be what is actually being refused, which is a different set and, at "
                    + "the unsafe default, a different size: " + line);
            assertTrue(line.contains("scanned=331") && line.contains("failed=0"),
                "and the other two must survive the refactor: " + line);
        } finally {
            synchronized (SafetyGate.deniedBySafety) {
                SafetyGate.deniedBySafety.clear();
                SafetyGate.deniedBySafety.addAll(saved);
            }
        }
    }

    @Test
    void theStatusScanLineDoesNotReuseTheLogLinesLabel() throws Exception {
        java.util.Set<String> constants = new java.util.LinkedHashSet<>();
        java.util.Set<String> calls = new java.util.LinkedHashSet<>();
        try (java.io.InputStream in = ScanSummaryTest.class
                .getResourceAsStream("/dev/pathweaver/command/PathWeaverCommand$ScanCounts.class")) {
            org.junit.jupiter.api.Assertions.assertNotNull(in, "ScanCounts.class not readable");
            new org.objectweb.asm.ClassReader(in.readAllBytes()).accept(
                new org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9) {
                    @Override public org.objectweb.asm.MethodVisitor visitMethod(
                            int a, String n, String d, String sg, String[] ex) {
                        if (!n.equals("line") && !n.equals("of")) return null;
                        return new org.objectweb.asm.MethodVisitor(org.objectweb.asm.Opcodes.ASM9) {
                            @Override public void visitLdcInsn(Object value) {
                                if (value instanceof String text) constants.add(text);
                            }
                            // String concatenation compiles to invokedynamic, and the literal parts
                            // live in the bootstrap recipe rather than in an LDC. Reading only LDC
                            // made this contract pass over a line whose text it never saw.
                            @Override public void visitInvokeDynamicInsn(String n2, String d2,
                                    org.objectweb.asm.Handle handle, Object... bootstrapArgs) {
                                for (Object arg : bootstrapArgs) {
                                    if (arg instanceof String text) constants.add(text);
                                }
                            }
                            @Override public void visitFieldInsn(int op, String o, String f,
                                                                 String d2) {
                                calls.add(o + "." + f);
                            }
                        };
                    }
                }, org.objectweb.asm.ClassReader.SKIP_FRAMES);
        }
        assertTrue(constants.stream().anyMatch(c -> c.contains("deniedByScan=")),
            "status must name what the scan FOUND distinctly: " + constants);
        assertTrue(constants.stream().anyMatch(c -> c.contains("enforced=")),
            "status must name what is ENFORCED distinctly: " + constants);
        assertFalse(constants.stream().anyMatch(c -> c.contains("deniedFamilies=")),
            "and must not reuse the startup log's label, which counts the other one: " + constants);
        assertTrue(calls.contains("dev/pathweaver/gate/SafetyGate.deniedBySafety"),
            "the enforced count must come from the set dispatch actually consults, which is the same "
                + "expression the startup log reports: " + calls);
    }


    /**
     * The status text itself, now that there is something to assert it against.
     *
     * <p>`status` was a `private static void (CommandSourceStack)` with no seam, so a review could
     * compile mutations inside it that no test could see. The line producer is the seam; this is the
     * assertion that makes having one worth anything.
     */
    @Test
    void theStatusTextReportsTheTierInForceAndTheScanCounts() {
        java.util.List<String> lines = PathWeaverCommand.statusLines();
        assertFalse(lines.isEmpty(), "status must say something");
        assertTrue(lines.get(0).contains("PathWeaver status"), "first line: " + lines.get(0));
        assertTrue(lines.stream().anyMatch(l -> l.contains("tier in force")),
            "an operator who switched tier mid-session must be told which one is actually running, "
                + "not which one is on disk: " + lines);
        assertTrue(lines.stream().anyMatch(l -> l.contains("deniedByScan=")
                && l.contains("enforced=")),
            "both scan counts must be present and separately named: " + lines);
    }

    /**
     * A tripped family must be announced, and announced as a runtime failure.
     *
     * <p>The scan narrative cannot describe it: it is handed the scan's findings, so a trip is either
     * invisible there or explained as "an evaluator that cannot be cloned on this JVM". Both are
     * causes the code would be inventing.
     */
    @Test
    void aTrippedFamilyIsNamedInStatusAndNotBlamedOnTheScan() {
        assertTrue(PathWeaverCommand.statusLines().stream()
                .noneMatch(l -> l.contains("switched OFF")),
            "nothing may be announced before anything has failed");
        try {
            dev.pathweaver.gate.SafetyGate.tripRuntimeFailure(WalkNodeEvaluator.class);
            java.util.List<String> lines = PathWeaverCommand.statusLines();
            assertTrue(lines.stream().anyMatch(l -> l.contains("threw on a worker")),
                "a switched-off family must be named, and named for what happened: " + lines);
            assertTrue(lines.stream().anyMatch(l -> l.contains("WalkNodeEvaluator")),
                "including which family: " + lines);
        } finally {
            dev.pathweaver.gate.SafetyGate.resetRuntimeFailureDenials();
        }
    }


    /**
     * The end-of-tick handler must publish the tick the breaker measures its window against.
     *
     * <p>Deleting that one line left the whole suite green, and the consequence is not visible from
     * anywhere: {@code currentTick} stays 0 forever, the window never elapses, and the breaker
     * quietly degrades into the pure cumulative counter that the design says "converges on a certain
     * trip given enough uptime". The pool-to-breaker wiring was pinned for exactly this reason; this
     * is the other half of the same seam.
     */
    @Test
    void theEndOfTickHandlerPublishesTheTickToTheBreaker() throws Exception {
        java.util.Set<String> calls = new java.util.LinkedHashSet<>();
        try (java.io.InputStream in = ScanSummaryTest.class
                .getResourceAsStream("/dev/pathweaver/PathWeaverRuntime.class")) {
            org.junit.jupiter.api.Assertions.assertNotNull(in, "PathWeaverRuntime.class not readable");
            new org.objectweb.asm.ClassReader(in.readAllBytes()).accept(
                new org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9) {
                    @Override public org.objectweb.asm.MethodVisitor visitMethod(
                            int a, String n, String d, String sg, String[] ex) {
                        if (!n.equals("onEndTick")) return null;
                        return new org.objectweb.asm.MethodVisitor(org.objectweb.asm.Opcodes.ASM9) {
                            @Override public void visitMethodInsn(int op, String o, String m,
                                                                  String md, boolean itf) {
                                calls.add(o + "." + m);
                            }
                        };
                    }
                }, org.objectweb.asm.ClassReader.SKIP_FRAMES);
        }
        assertTrue(calls.contains("dev/pathweaver/gate/WorkerFailureBreaker.setTick"),
            "onEndTick must publish the tick, or the failure window never advances and the breaker "
                + "becomes the cumulative counter it was designed not to be: " + calls);
    }

    /**
     * And the server-start hook must reset it.
     *
     * <p>Deleting {@code ModAttribution.reset()} from that path leaves the one-shot report burnt, so
     * in singleplayer the first failure of every later world is silent -- verbatim the defect the
     * design cites as the reason reset is per-server rather than per-JVM.
     */
    @Test
    void theServerStartHookResetsTheBreaker() throws Exception {
        java.util.Set<String> calls = new java.util.LinkedHashSet<>();
        try (java.io.InputStream in = ScanSummaryTest.class
                .getResourceAsStream("/dev/pathweaver/PathWeaverRuntime.class")) {
            org.junit.jupiter.api.Assertions.assertNotNull(in, "PathWeaverRuntime.class not readable");
            new org.objectweb.asm.ClassReader(in.readAllBytes()).accept(
                new org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9) {
                    @Override public org.objectweb.asm.MethodVisitor visitMethod(
                            int a, String n, String d, String sg, String[] ex) {
                        if (!n.equals("onServerStarting")) return null;
                        return new org.objectweb.asm.MethodVisitor(org.objectweb.asm.Opcodes.ASM9) {
                            @Override public void visitMethodInsn(int op, String o, String m,
                                                                  String md, boolean itf) {
                                calls.add(o + "." + m);
                            }
                        };
                    }
                }, org.objectweb.asm.ClassReader.SKIP_FRAMES);
        }
        assertTrue(calls.contains("dev/pathweaver/gate/WorkerFailureBreaker.reset"),
            "a trip and a burnt one-shot must not survive into the next world: " + calls);
    }

}
