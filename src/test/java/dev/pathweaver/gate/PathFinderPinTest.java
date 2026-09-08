package dev.pathweaver.gate;

import net.minecraft.world.level.pathfinder.PathFinder;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one pin for vanilla {@code PathFinder}, and the scan that runs against the bytes it fixes.
 *
 * <p>Four audits refuse an eligible evaluator unless this digest matches, and nothing checked that it
 * matches the class actually shipped. A wrong digest fails closed, so it would not have corrupted
 * anything; it would have turned every audited exemption off and given no reason a reader could act
 * on. Three files also kept their own copy of it, agreeing by luck rather than by anything checking.
 *
 * <p>The scan is a direct-call scan of one class. It used to be called a reachability proof and to
 * run on unpinned bytes, which claimed the transitive property from evidence for the immediate one.
 * Pinned, it means what it says: these exact bytes, audited once, call {@code PathNavigation} from no
 * method of their own.
 */
class PathFinderPinTest {

    private static byte[] pathFinderBytes() throws Exception {
        return AuditedMixinCompatibility.readClassBytes(PathFinder.class);
    }

    /** The pin is the digest of the class this build runs. */
    @Test
    void thePinMatchesTheVanillaClassOnTheClasspath() throws Exception {
        List<String> diagnostics = new ArrayList<>();
        AuditedMixinCompatibility.checkHash("vanilla PathFinder", pathFinderBytes(),
            AuditedMixinCompatibility.PATH_FINDER_SHA, diagnostics);
        assertEquals(List.of(), diagnostics,
            "the pinned digest is not the one the shipped PathFinder has; every audited exemption "
                + "using it refuses, with no reason an operator can act on");
    }

    /**
     * The negative control for the pin. Without it the test above would also pass against a check
     * that accepts anything.
     */
    @Test
    void aChangedPathFinderFailsThePin() throws Exception {
        byte[] altered = pathFinderBytes();
        altered[altered.length - 1] ^= 0x01;

        List<String> diagnostics = new ArrayList<>();
        AuditedMixinCompatibility.checkHash("vanilla PathFinder", altered,
            AuditedMixinCompatibility.PATH_FINDER_SHA, diagnostics);
        assertEquals(1, diagnostics.size(), "a changed PathFinder passed the pin");
        assertTrue(diagnostics.getFirst().contains("vanilla PathFinder"), diagnostics.toString());
    }

    /** The scan says nothing about the pinned class, which is the fact the exemptions rest on. */
    @Test
    void thePinnedPathFinderCallsPathNavigationFromNoMethodOfItsOwn() throws Exception {
        List<String> diagnostics = new ArrayList<>();
        AuditedMixinCompatibility.verifyPathFinderMakesNoDirectCallIntoPathNavigation(
            pathFinderBytes(), diagnostics);
        assertEquals(List.of(), diagnostics);
    }

    /**
     * The negative control for the scan.
     *
     * <p>The pinned class with ONE call spliced into it, so the only thing that differs is the thing
     * being detected. Feeding it an unrelated class instead would fail on the descriptor check first
     * and never exercise the call scan at all, which is a control that proves the wrong instrument
     * works.
     */
    @Test
    void aPathFinderThatDoesCallPathNavigationIsReported() throws Exception {
        List<String> diagnostics = new ArrayList<>();
        AuditedMixinCompatibility.verifyPathFinderMakesNoDirectCallIntoPathNavigation(
            withOnePathNavigationCall(pathFinderBytes()), diagnostics);

        assertEquals(1, diagnostics.size(),
            "expected exactly the spliced call to be reported: " + diagnostics);
        assertTrue(diagnostics.getFirst().contains("calls PathNavigation directly"),
            diagnostics.getFirst());
    }

    /** The pinned class, plus a single invoke on PathNavigation and nothing else changed. */
    private static byte[] withOnePathNavigationCall(byte[] original) {
        ClassNode node = new ClassNode();
        new ClassReader(original).accept(node, 0);
        MethodNode target = node.methods.stream()
            .filter(method -> method.instructions.size() > 0)
            .findFirst().orElseThrow();
        target.instructions.insert(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
            "net/minecraft/world/entity/ai/navigation/PathNavigation", "stop", "()V", false));
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }

    /**
     * A changed PathFinder produces a denial the Lithium audit actually returns.
     *
     * <p>The earlier version of this looked for the digest literal inside the compiled verify method.
     * That proves the pin is mentioned, not that a mismatch reaches the caller: a hash check writing
     * into a list nobody returns keeps the literal exactly where the scan would find it. So this runs
     * verify and reads its output.
     *
     * <p>The other artifacts in the bundle are deliberately junk, because the real Lithium jar is not
     * on the test classpath and every other check failing is fine here. What matters is the presence
     * or absence of one specific line among the diagnostics, and the two runs differ only in the
     * PathFinder bytes.
     */
    @Test
    void aChangedPathFinderProducesADenialTheLithiumAuditReturns() throws Exception {
        List<String> withRealBytes = LithiumPathfindingCompatibility.verify(bundleWith(pathFinderBytes()));
        List<String> withChangedBytes = LithiumPathfindingCompatibility.verify(
            bundleWith(oneByteDifferent(pathFinderBytes())));

        assertTrue(mentionsPathFinderMismatch(withChangedBytes),
            "a changed PathFinder produced no returned denial: " + withChangedBytes);
        assertFalse(mentionsPathFinderMismatch(withRealBytes),
            "the pinned PathFinder was denied, so the assertion above is not about the pin: "
                + withRealBytes);

        // The positive control on the harness itself: verify really ran and really produced output.
        // A verify that returned nothing at all would satisfy the absence assertion above.
        assertFalse(withRealBytes.isEmpty(),
            "verify returned no diagnostics for a bundle of junk artifacts; it did not run");
    }

    private static boolean mentionsPathFinderMismatch(List<String> diagnostics) {
        return diagnostics.stream().anyMatch(line ->
            line.contains("vanilla PathFinder") && line.contains("hash mismatch"));
    }

    private static byte[] oneByteDifferent(byte[] bytes) {
        byte[] altered = bytes.clone();
        altered[altered.length - 1] ^= 0x01;
        return altered;
    }

    /** Junk for every artifact the audit checks, and the given bytes for vanilla PathFinder. */
    private static LithiumPathfindingCompatibility.Bundle bundleWith(byte[] pathFinder) {
        byte[] junk = new byte[] {1, 2, 3};
        return new LithiumPathfindingCompatibility.Bundle(junk, junk, junk, junk, junk, junk, junk,
            junk, junk, junk, junk, junk, pathFinder);
    }

    /**
     * The Lithium audit passes the pin to its hash check.
     *
     * <p>Kept beside the executed test, narrower on purpose: it says which constant is used, which
     * the behavioural test above cannot, since any digest that fails for a changed class and passes
     * for the real one would satisfy it.
     */
    @Test
    void theLithiumAuditUsesThatConstantAndNotAnotherDigest() throws Exception {
        ClassNode node = new ClassNode();
        new ClassReader(AuditedMixinCompatibility.readClassBytes(
            LithiumPathfindingCompatibility.class)).accept(node, 0);

        List<String> methodsHoldingThePin = new ArrayList<>();
        for (MethodNode method : node.methods) {
            for (var insn : method.instructions) {
                if (insn instanceof LdcInsnNode ldc
                        && AuditedMixinCompatibility.PATH_FINDER_SHA.equals(ldc.cst)) {
                    methodsHoldingThePin.add(method.name);
                }
            }
        }
        assertTrue(methodsHoldingThePin.contains("verify"),
            "the Lithium audit does not use the PathFinder pin; found it in " + methodsHoldingThePin);
    }
}
