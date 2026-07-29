package dev.pathweaver.gate;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.*;

class FabricLandPathRegistryLatchTest {
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
