package dev.pathweaver.mixin;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.InputStream;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Four dispatch guards that every test asserted the PREDICATE of, and nothing asserted are CALLED.
 *
 * <p>{@code PathNavigationRoutingContractTest} exists because of exactly this failure — its own
 * comment records six mutations surviving the whole suite at one call site, "because every test
 * asserted the PREDICATE and nothing asserted that dispatch calls it". It pins four guards. These
 * four are in the same chain and were pinned by nothing.
 *
 * <p>{@code owesEpilogue} is the one that matters. Its own comment at the call site says removing it
 * lets two {@code prepare()} calls run against one live mob, after which "the mob keeps 6.0/4.0
 * forever" — a permanent, silent corruption of that mob's pathfinding malus. Deleting the line left
 * every unit test green.
 *
 * <p>Presence, not ordering. Ordering for these four is not independently load-bearing the way the
 * gate sequence is, and asserting an order nobody derived would pin an accident.
 */
class DispatchGatePresenceContractTest {

    private record Call(String owner, String method) { }

    private static Set<Call> callsInDispatchDecision() throws Exception {
        Set<Call> calls = new LinkedHashSet<>();
        try (InputStream in = DispatchGatePresenceContractTest.class
                .getResourceAsStream("/dev/pathweaver/mixin/PathNavigationMixin.class")) {
            assertNotNull(in, "PathNavigationMixin.class not readable");
            new ClassReader(in.readAllBytes()).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public MethodVisitor visitMethod(int access, String name, String desc,
                                                           String sig, String[] ex) {
                    if (!name.equals("pathweaver$asyncCreatePath")) return null;
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override public void visitMethodInsn(int opcode, String owner,
                                                              String method, String md, boolean itf) {
                            calls.add(new Call(owner, method));
                        }
                    };
                }
            }, 0);
        }
        assertTrue(calls.size() > 5,
            "the dispatch decision was not found or is empty, so this test asserts nothing");
        return calls;
    }

    @Test void theEpilogueGuardIsActuallyCalled() throws Exception {
        assertTrue(callsInDispatchDecision().contains(
                new Call("dev/pathweaver/async/EntityInstallSink", "owesEpilogue")),
            "dispatch no longer asks whether this mob still owes an evaluator epilogue. Two "
                + "prepare() calls then run against one live mob and its pathfinding malus is "
                + "permanently corrupted -- the call site's own comment says the mob 'keeps 6.0/4.0 "
                + "forever'. Deleting this line leaves every unit test green, which is why it is "
                + "pinned here.");
    }

    @Test void theFailureCooldownIsActuallyConsulted() throws Exception {
        assertTrue(callsInDispatchDecision().contains(
                new Call("dev/pathweaver/async/EntityInstallSink", "shouldForceSync")),
            "dispatch no longer consults the per-entity failure cooldown, so a mob whose searches "
                + "keep throwing would be dispatched again every tick instead of falling back");
    }

    @Test void theOneRequestPerMobGuardIsActuallyCalled() throws Exception {
        assertTrue(callsInDispatchDecision().contains(
                new Call("dev/pathweaver/async/EntityInstallSink", "isRegistered")),
            "dispatch no longer refuses while a request for this mob is already in flight; two live "
                + "searches for one navigation is the state the whole sink is built to exclude");
    }

    @Test void thePathFinderExactClassCheckIsActuallyPerformed() throws Exception {
        Set<Call> calls = callsInDispatchDecision();
        assertTrue(calls.contains(new Call("java/lang/Object", "getClass"))
                || calls.contains(new Call("net/minecraft/world/level/pathfinder/PathFinder",
                                           "getClass")),
            "dispatch no longer checks the PathFinder's exact class. A mod shipping a PathFinder "
                + "subclass paired with a stock evaluator would then run its own A* on every "
                + "synchronous fallback and vanilla's on every async dispatch -- routing that flips "
                + "with pool load, which nobody can report reproducibly");
    }
}
