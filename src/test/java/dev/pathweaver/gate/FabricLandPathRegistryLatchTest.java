package dev.pathweaver.gate;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.*;

class FabricLandPathRegistryLatchTest {
    @AfterEach void reset() {
        FabricLandPathRegistryLatch.resetForTests();
    }

    @Test void unverifiedHooksFailClosedEvenWhenRegistryAppearsEmpty() {
        FabricLandPathRegistryLatch.resetForTests();
        assertFalse(FabricLandPathRegistryLatch.allowsWalkDispatch());
        assertFalse(FabricLandPathRegistryLatch.allowsWalkInstall());
    }

    @Test void registrationBeforeDispatchDeniesMonotonically() {
        FabricLandPathRegistryLatch.publishHooksVerified(true);
        FabricLandPathRegistryLatch.beforeProviderMutation();
        assertFalse(FabricLandPathRegistryLatch.allowsWalkDispatch());
        assertFalse(FabricLandPathRegistryLatch.allowsWalkInstall());
        FabricLandPathRegistryLatch.publishHooksVerified(true);
        assertFalse(FabricLandPathRegistryLatch.allowsWalkDispatch(),
            "verification must never clear an observed registration");
    }

    @Test void dispatchFalseThenRegistrationBeforeInstallDiscardsExactRequest() {
        FabricLandPathRegistryLatch.publishHooksVerified(true);
        assertTrue(FabricLandPathRegistryLatch.allowsWalkDispatch(), "D reads false");
        FabricLandPathRegistryLatch.beforeProviderMutation();               // R before I
        assertFalse(FabricLandPathRegistryLatch.allowsWalkInstall(), "I must deny");
    }

    @Test void installBeforeRegistrationLinearizesWhileEmptyAndFutureDispatchesDeny() {
        FabricLandPathRegistryLatch.publishHooksVerified(true);
        assertTrue(FabricLandPathRegistryLatch.allowsWalkDispatch(), "D reads false");
        assertTrue(FabricLandPathRegistryLatch.allowsWalkInstall(), "I precedes R");
        FabricLandPathRegistryLatch.beforeProviderMutation();
        assertFalse(FabricLandPathRegistryLatch.allowsWalkDispatch());
    }

    @Test void atomicPublicationMakesConcurrentRegistrationVisibleBeforeInstall() throws Exception {
        FabricLandPathRegistryLatch.publishHooksVerified(true);
        assertTrue(FabricLandPathRegistryLatch.allowsWalkDispatch());
        CountDownLatch marked = new CountDownLatch(1);
        Thread registration = new Thread(() -> {
            FabricLandPathRegistryLatch.beforeProviderMutation();
            marked.countDown();
        });
        registration.start();
        marked.await();
        registration.join();
        assertFalse(FabricLandPathRegistryLatch.allowsWalkInstall());
    }
}
