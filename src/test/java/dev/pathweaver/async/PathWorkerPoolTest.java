package dev.pathweaver.async;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class PathWorkerPoolTest {
    PathWorkerPool pool;

    @BeforeEach void up() { pool = new PathWorkerPool(); pool.start(2, 4); }
    @AfterEach void down() { pool.shutdown(); }

    @Test void runsOffCallingThreadAndCompletes() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Thread> ran = new AtomicReference<>();
        PathRequest req = new PathRequest(key(1), 0L,
            () -> { ran.set(Thread.currentThread()); return null; },
            p -> done.countDown());
        assertTrue(pool.submit(req));
        assertTrue(done.await(2, TimeUnit.SECONDS));
        assertNotSame(Thread.currentThread(), ran.get());
    }

    @Test void inFlightCapRejects() {
        CountDownLatch block = new CountDownLatch(1);
        for (int i = 0; i < 4; i++) {
            int id = i;
            assertTrue(pool.submit(new PathRequest(key(id), 0L, () -> {
                block.await();
                return null;
            }, p -> { })));
        }
        assertEquals(4, pool.inFlight());
        assertFalse(pool.submit(new PathRequest(key(99), 0L, () -> null, p -> { })));
        block.countDown();
    }

    @Test void nullSearchCompletesAsNoPathWithoutFailureAccounting() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<PathOutcome> got = new AtomicReference<>();
        assertTrue(pool.submit(new PathRequest(key(2), 0L, () -> null,
            outcome -> { got.set(outcome); done.countDown(); })));
        assertTrue(done.await(2, TimeUnit.SECONDS));
        assertEquals(PathOutcome.Status.NO_PATH, got.get().status());
        assertEquals(0L, pool.failureCount());
    }

    @Test void taskExceptionCompletesAsFailedWithItsCause() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<PathOutcome> got = new AtomicReference<>();
        assertTrue(pool.submit(new PathRequest(key(3), 0L,
            () -> { throw new IllegalStateException("boom"); },
            outcome -> { got.set(outcome); done.countDown(); })));
        assertTrue(done.await(2, TimeUnit.SECONDS));
        assertEquals(PathOutcome.Status.FAILED, got.get().status());
        assertEquals("boom", got.get().failure().getMessage());
        assertEquals(1L, pool.failureCount());
    }

    @Test void throwingCompletionConsumerIsCountedRatherThanSwallowed() throws Exception {
        CountDownLatch attempted = new CountDownLatch(1);
        assertTrue(pool.submit(new PathRequest(key(4), 0L, () -> null, outcome -> {
            attempted.countDown();
            throw new IllegalStateException("delivery boom");
        })));
        assertTrue(attempted.await(2, TimeUnit.SECONDS));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (pool.completionFailureCount() == 0L && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertEquals(1L, pool.completionFailureCount());
        assertEquals(0L, pool.failureCount());
    }

    @Test void throwingFailureReporterCannotPreventFailedDelivery() throws Exception {
        pool.shutdown();
        pool = new PathWorkerPool() {
            @Override protected void reportSearchFailure(long count, Throwable failure) {
                throw new IllegalStateException("logger boom");
            }
        };
        pool.start(1, 1);
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<PathOutcome> got = new AtomicReference<>();
        assertTrue(pool.submit(new PathRequest(key(5), 0L,
            () -> { throw new IllegalArgumentException("search boom"); },
            outcome -> { got.set(outcome); done.countDown(); })));

        assertTrue(done.await(2, TimeUnit.SECONDS));
        assertEquals(PathOutcome.Status.FAILED, got.get().status());
        assertEquals("search boom", got.get().failure().getMessage());
        assertEquals(1L, pool.failureCount());
    }

    @Test void restartAfterStuckTaskResetsInFlightAndAcceptsWork() throws Exception {
        CountDownLatch block = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(2);
        for (int i = 0; i < 4; i++) {
            int id = i;
            pool.submit(new PathRequest(key(id), 0L, () -> {
                started.countDown();
                block.await();
                return null;
            }, p -> { }));
        }
        assertTrue(started.await(2, TimeUnit.SECONDS));
        assertTrue(pool.inFlight() > 0);

        pool.shutdown();
        pool.start(2, 4);
        assertEquals(0, pool.inFlight());

        CountDownLatch done = new CountDownLatch(1);
        assertTrue(pool.submit(new PathRequest(key(9), 1L, () -> null, p -> done.countDown())));
        assertTrue(done.await(2, TimeUnit.SECONDS));
        block.countDown();
    }

    @Test void doubleStartIsIdempotent() {
        pool.start(2, 4);
        assertEquals(0, pool.inFlight());
        assertTrue(pool.submit(new PathRequest(key(1), 0L, () -> null, p -> { })));
    }

    @Test void interruptIgnoringOldGenerationCannotDecrementNewGenerationCapacity() throws Exception {
        pool.shutdown();
        pool.start(1, 1);
        CountDownLatch oldStarted = new CountDownLatch(1);
        CountDownLatch releaseOld = new CountDownLatch(1);
        CountDownLatch oldDone = new CountDownLatch(1);
        assertTrue(pool.submit(new PathRequest(key(1), 0L, () -> {
            oldStarted.countDown();
            while (releaseOld.getCount() > 0) {
                try { releaseOld.await(); } catch (InterruptedException ignored) { }
            }
            return null;
        }, p -> oldDone.countDown())));
        assertTrue(oldStarted.await(2, TimeUnit.SECONDS));

        pool.shutdown();
        pool.start(1, 1);
        CountDownLatch releaseNew = new CountDownLatch(1);
        assertTrue(pool.submit(new PathRequest(key(2), 0L, () -> {
            releaseNew.await();
            return null;
        }, p -> { })));
        assertEquals(1, pool.inFlight());

        releaseOld.countDown();
        assertTrue(oldDone.await(2, TimeUnit.SECONDS));
        assertEquals(1, pool.inFlight(),
            "an old worker completion must only decrement its own generation");
        releaseNew.countDown();
    }

    @Test void oldGenerationFailureCannotIncrementNewGenerationFailureCount() throws Exception {
        pool.shutdown();
        pool.start(1, 1);
        CountDownLatch oldStarted = new CountDownLatch(1);
        CountDownLatch releaseOld = new CountDownLatch(1);
        CountDownLatch oldDone = new CountDownLatch(1);
        assertTrue(pool.submit(new PathRequest(key(3), 0L, () -> {
            oldStarted.countDown();
            while (releaseOld.getCount() > 0) {
                try { releaseOld.await(); } catch (InterruptedException ignored) { }
            }
            throw new IllegalStateException("old generation failure");
        }, p -> oldDone.countDown())));
        assertTrue(oldStarted.await(2, TimeUnit.SECONDS));

        pool.shutdown();
        pool.start(1, 1);
        assertEquals(0L, pool.failureCount());
        releaseOld.countDown();
        assertTrue(oldDone.await(2, TimeUnit.SECONDS));
        assertEquals(0L, pool.failureCount());
    }

    private static RequestKey key(int entityId) {
        return new RequestKey(1L, entityId + 1L, entityId);
    }
}
