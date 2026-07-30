package dev.pathweaver.gate;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.*;

class FabricLandPathRegistryLatchTest {
    /**
     * An audited dynamic provider is frozen like a static one, but the evidence is an audit rather
     * than proven inertness, so it must count only above the strict tier. The tier is read here, at
     * dispatch, because registration happens before PathWeaver's config is loaded.
     */
    @Test void certifiedProviderIsHonouredOnlyAboveTheStrictTier() {
        var state = FabricLandPathRegistryLatch.isolatedState();
        state.publishHooksVerified(true);
        assertTrue(state.allowsWalk(false), "nothing registered yet");

        state.certifiedProviderRegistered();
        assertFalse(state.allowsWalk(false),
            "a frozen table is evidence, not proof, so STRICT denies");
        assertTrue(state.allowsWalk(true), "AUDITED may honour it");
    }

    @Test void certificationNeverReopensALatchAPlainRegistrationClosed() {
        var state = FabricLandPathRegistryLatch.isolatedState();
        state.publishHooksVerified(true);
        state.certifiedProviderRegistered();
        state.beforeProviderMutation();
        for (boolean audited : new boolean[] {false, true}) {
            assertFalse(state.allowsWalk(audited), "allowsAudited=" + audited);
        }
    }

    @Test void anUnpublishedPolicyDeniesCertifiedProviders() {
        // An unpublished policy answers false, which is the same input STRICT supplies.
        var state = FabricLandPathRegistryLatch.isolatedState();
        state.publishHooksVerified(true);
        state.certifiedProviderRegistered();
        assertFalse(state.allowsWalk(ActiveCompatibilityPolicy.isolatedState().allowsAudited()),
            "an aborted or absent scan must not admit evidence");
    }

    @Test void unverifiedHooksFailClosedEvenWhenRegistryAppearsEmpty() {
        var state = FabricLandPathRegistryLatch.isolatedState();
        assertFalse(state.allowsWalk());
    }

    @Test void registrationBeforeDispatchDeniesMonotonically() {
        var state = FabricLandPathRegistryLatch.isolatedState();
        state.publishHooksVerified(true);
        state.beforeProviderMutation();
        assertFalse(state.allowsWalk());
        state.publishHooksVerified(true);
        assertFalse(state.allowsWalk(), "verification must never clear an observed registration");
    }

    @Test void dispatchFalseThenRegistrationBeforeInstallDiscardsExactRequest() {
        var state = FabricLandPathRegistryLatch.isolatedState();
        state.publishHooksVerified(true);
        assertTrue(state.allowsWalk(), "D reads false");
        state.beforeProviderMutation(); // R before I
        assertFalse(state.allowsWalk(), "I must deny");
    }

    @Test void installBeforeRegistrationLinearizesWhileEmptyAndFutureDispatchesDeny() {
        var state = FabricLandPathRegistryLatch.isolatedState();
        state.publishHooksVerified(true);
        assertTrue(state.allowsWalk(), "D reads false");
        assertTrue(state.allowsWalk(), "I precedes R");
        state.beforeProviderMutation();
        assertFalse(state.allowsWalk());
    }

    @Test void atomicPublicationMakesConcurrentRegistrationVisibleBeforeInstall() throws Exception {
        var state = FabricLandPathRegistryLatch.isolatedState();
        state.publishHooksVerified(true);
        assertTrue(state.allowsWalk());
        CountDownLatch marked = new CountDownLatch(1);
        Thread registration = new Thread(() -> {
            state.beforeProviderMutation();
            marked.countDown();
        });
        registration.start();
        marked.await();
        registration.join();
        assertFalse(state.allowsWalk());
    }

    @Test void workerBypassCounterRecordsOnlyExplicitBypasses() {
        var state = FabricLandPathRegistryLatch.isolatedState();
        assertEquals(0L, state.workerProviderLookupBypasses());
        state.recordWorkerProviderLookupBypass();
        assertEquals(1L, state.workerProviderLookupBypasses());
    }
}
