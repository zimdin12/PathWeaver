package dev.pathweaver.async;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class SearchStartGateTest {
    @Test void acceptedWorkerWaitsForMainThreadStartAndObservesItsEffects() throws Exception {
        SearchStartGate gate = new SearchStartGate();
        CountDownLatch attempted = new CountDownLatch(1);
        CountDownLatch searched = new CountDownLatch(1);
        int[] callbackState = {0};
        try (var worker = Executors.newSingleThreadExecutor()) {
            var result = worker.submit(() -> {
                attempted.countDown();
                if (gate.awaitStart()) {
                    assertEquals(42, callbackState[0], "open must publish callback effects");
                    searched.countDown();
                    return true;
                }
                return false;
            });
            assertTrue(attempted.await(5, TimeUnit.SECONDS));
            assertEquals(1L, searched.getCount(), "search must not begin before the start callback");
            callbackState[0] = 42;
            gate.open();
            assertTrue(result.get(5, TimeUnit.SECONDS));
            assertEquals(0L, searched.getCount());
        }
    }

    @Test void cancellationAlwaysReleasesWaiterWithoutStartingSearch() throws Exception {
        SearchStartGate gate = new SearchStartGate();
        CountDownLatch attempted = new CountDownLatch(1);
        try (var worker = Executors.newSingleThreadExecutor()) {
            var result = worker.submit(() -> {
                attempted.countDown();
                return gate.awaitStart();
            });
            assertTrue(attempted.await(5, TimeUnit.SECONDS));
            gate.cancel();
            assertFalse(result.get(5, TimeUnit.SECONDS));
        }
    }
}
