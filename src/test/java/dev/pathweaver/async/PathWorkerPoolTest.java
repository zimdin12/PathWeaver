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
}
