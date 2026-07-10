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
        PathRequest req = new PathRequest(1, 0L,
            () -> { ran.set(Thread.currentThread()); return null; },
            p -> done.countDown());
        assertTrue(pool.submit(req));
        assertTrue(done.await(2, TimeUnit.SECONDS));
        assertNotSame(Thread.currentThread(), ran.get());
    }

    @Test void inFlightCapRejects() throws Exception {
        CountDownLatch block = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(2);
        for (int i = 0; i < 4; i++) {
            pool.submit(new PathRequest(i, 0L, () -> {
                started.countDown();
                block.await();
                return null;
            }, p -> {}));
        }
        // ensure the pool is saturated (all 4 counted in-flight)
        assertEquals(4, pool.inFlight());
        boolean accepted = pool.submit(new PathRequest(99, 0L, () -> null, p -> {}));
        assertFalse(accepted); // cap hit
        block.countDown();
    }

    @Test void taskExceptionCompletesWithNull() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Object> got = new AtomicReference<>("sentinel");
        pool.submit(new PathRequest(2, 0L,
            () -> { throw new RuntimeException("boom"); },
            p -> { got.set(p); done.countDown(); }));
        assertTrue(done.await(2, TimeUnit.SECONDS));
        assertNull(got.get());
    }

    /**
     * FIX 5: shutdown() drops queued tasks via shutdownNow(), so their finally (which decrements
     * inFlight) never runs. start() must reset inFlight to 0 or async permanently ratchets off after a
     * world reload. Here we wedge the pool, shut it down mid-flight, restart, and prove it accepts work.
     */
    @Test void restartAfterStuckTaskResetsInFlightAndAcceptsWork() throws Exception {
        CountDownLatch block = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(2);
        for (int i = 0; i < 4; i++) {
            pool.submit(new PathRequest(i, 0L, () -> { started.countDown(); block.await(); return null; }, p -> {}));
        }
        assertTrue(started.await(2, TimeUnit.SECONDS));
        assertTrue(pool.inFlight() > 0);

        pool.shutdown();                 // shutdownNow(): queued tasks dropped, running ones interrupted
        pool.start(2, 4);                // simulate a world reload
        assertEquals(0, pool.inFlight(), "inFlight must reset on start()");

        CountDownLatch done = new CountDownLatch(1);
        assertTrue(pool.submit(new PathRequest(9, 1L, () -> null, p -> done.countDown())),
            "a restarted pool must accept new work");
        assertTrue(done.await(2, TimeUnit.SECONDS));
        block.countDown();
    }

    @Test void doubleStartIsIdempotent() {
        pool.start(2, 4);                // second start without stop: must not throw or leak inFlight
        assertEquals(0, pool.inFlight());
        assertTrue(pool.submit(new PathRequest(1, 0L, () -> null, p -> {})));
    }
}
