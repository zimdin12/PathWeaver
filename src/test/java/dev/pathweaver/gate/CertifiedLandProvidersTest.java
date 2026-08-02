package dev.pathweaver.gate;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.PathType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Certification is what lets a mod's path-type rule survive onto a worker without the mod's code
 * running there. If it silently certified less than the whole input domain, a worker would answer
 * some block states and diverge from vanilla on the rest — which is a wrong path, not a crash, and
 * so would not announce itself.
 */
class CertifiedLandProvidersTest {

    /**
     * These cases need real {@code Blocks}, which pulls in the DataFixerUpper schema and does not
     * initialise in every bare test JVM. Skip rather than fail when that happens: a red suite that
     * depends on class-initialisation order teaches people to ignore red. The same properties are
     * asserted in {@code FabricAggregateWalkRoutingGameTest}, which runs inside a real server, so
     * skipping here never leaves the behaviour unverified.
     */
    /**
     * These need real {@code Blocks}. They used to boot them here behind an
     * {@code Assumptions.assumeTrue}, which meant that when an earlier test class poisoned
     * {@code Blocks}' static initialiser, all seven of these skipped and the suite still reported
     * green. Booting is now a session-level concern
     * ({@link dev.pathweaver.testsupport.MinecraftBootstrapListener}) that runs before any test class
     * is loaded, and this asserts rather than assumes: if the registries are missing, these tests are
     * unverified and must say so in red.
     */
    @BeforeAll
    static void bootMinecraftRegistries() {
        dev.pathweaver.testsupport.MinecraftBootstrapListener.bootOnce();
        assertFalse(Blocks.STONE.getStateDefinition().getPossibleStates().isEmpty(),
            "block registries are empty, so nothing below is actually being certified");
    }

    @BeforeEach
    @AfterEach
    void clearFrozenAnswers() {
        CertifiedLandProviders.resetForTests();
    }

    @Test
    void freezesEveryStateAndBothNeighbourValues() {
        int states = Blocks.OAK_FENCE.getStateDefinition().getPossibleStates().size();
        assertTrue(states > 1, "need a multi-state block for this to mean anything");

        assertTrue(CertifiedLandProviders.certify(Blocks.OAK_FENCE,
            (state, isNeighbour) -> isNeighbour ? PathType.FIRE_IN_NEIGHBOR : PathType.FIRE));

        assertTrue(CertifiedLandProviders.isCertified(Blocks.OAK_FENCE));
        assertEquals(states, CertifiedLandProviders.certifiedStateCount());
        for (BlockState state : Blocks.OAK_FENCE.getStateDefinition().getPossibleStates()) {
            assertEquals(PathType.FIRE,
                CertifiedLandProviders.frozenProvider().getPathType(state, false));
            assertEquals(PathType.FIRE_IN_NEIGHBOR,
                CertifiedLandProviders.frozenProvider().getPathType(state, true));
        }
    }

    /** The provider runs once per input at certification and never again. */
    @Test
    void thirdPartyProviderCodeNeverRunsOnLookup() {
        AtomicInteger calls = new AtomicInteger();
        CertifiedLandProviders.certify(Blocks.STONE, (state, isNeighbour) -> {
            calls.incrementAndGet();
            return PathType.BLOCKED;
        });
        int afterCertify = calls.get();
        assertTrue(afterCertify > 0, "certification must actually evaluate the provider");

        for (int i = 0; i < 25; i++) {
            CertifiedLandProviders.frozenProvider()
                .getPathType(Blocks.STONE.defaultBlockState(), false);
        }
        assertEquals(afterCertify, calls.get(),
            "a lookup re-entered third-party provider code, so it could run on a worker");
    }

    /**
     * A provider may legitimately return null for a state, meaning "no opinion, use vanilla".
     * The immutable map factories reject null values, and using one here previously turned a
     * perfectly valid provider into a certification failure.
     */
    @Test
    void acceptsProvidersThatDeclineToAnswerForSomeStates() {
        assertTrue(CertifiedLandProviders.certify(Blocks.STONE,
            (state, isNeighbour) -> isNeighbour ? PathType.BLOCKED : null));
        assertTrue(CertifiedLandProviders.isCertified(Blocks.STONE));
        assertNull(CertifiedLandProviders.frozenProvider()
            .getPathType(Blocks.STONE.defaultBlockState(), false));
        assertEquals(PathType.BLOCKED, CertifiedLandProviders.frozenProvider()
            .getPathType(Blocks.STONE.defaultBlockState(), true));
    }

    /** A throwing provider must not leave a partial table behind. */
    @Test
    void refusesCertificationWhenTheProviderThrows() {
        assertFalse(CertifiedLandProviders.certify(Blocks.STONE, (state, isNeighbour) -> {
            throw new IllegalStateException("provider blew up");
        }));
        assertFalse(CertifiedLandProviders.isCertified(Blocks.STONE));
        assertEquals(0, CertifiedLandProviders.certifiedStateCount());
    }

    @Test
    void anUncertifiedBlockIsNotReportedAsCertified() {
        CertifiedLandProviders.certify(Blocks.STONE, (state, isNeighbour) -> PathType.BLOCKED);
        assertTrue(CertifiedLandProviders.isCertified(Blocks.STONE));
        assertFalse(CertifiedLandProviders.isCertified(Blocks.OAK_FENCE),
            "a block nobody registered must not inherit another block's certification");
    }

    /** Registering a second block must not drop the first one's frozen answers. */
    @Test
    void certifyingASecondBlockPreservesTheFirst() {
        CertifiedLandProviders.certify(Blocks.STONE, (state, isNeighbour) -> PathType.BLOCKED);
        CertifiedLandProviders.certify(Blocks.OAK_FENCE, (state, isNeighbour) -> PathType.WATER);
        assertTrue(CertifiedLandProviders.isCertified(Blocks.STONE));
        assertTrue(CertifiedLandProviders.isCertified(Blocks.OAK_FENCE));
        assertEquals(PathType.BLOCKED, CertifiedLandProviders.frozenProvider()
            .getPathType(Blocks.STONE.defaultBlockState(), false));
        assertEquals(PathType.WATER, CertifiedLandProviders.frozenProvider()
            .getPathType(Blocks.OAK_FENCE.defaultBlockState(), false));
    }

    /** A later registration for the same block wins, as it does in Fabric's own map. */
    @Test
    void reRegisteringTheSameBlockReplacesItsAnswers() {
        CertifiedLandProviders.certify(Blocks.STONE, (state, isNeighbour) -> PathType.BLOCKED);
        CertifiedLandProviders.certify(Blocks.STONE, (state, isNeighbour) -> PathType.WATER);
        assertEquals(PathType.WATER, CertifiedLandProviders.frozenProvider()
            .getPathType(Blocks.STONE.defaultBlockState(), false));
    }
}
