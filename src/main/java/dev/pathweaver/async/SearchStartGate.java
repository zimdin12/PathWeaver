package dev.pathweaver.async;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One-shot acceptance barrier: an accepted worker cannot read live search inputs until the main
 * thread has completed the corresponding start callback. Cancellation releases the worker without
 * authorizing a search.
 */
public final class SearchStartGate {
    private static final int WAITING = 0;
    private static final int OPEN = 1;
    private static final int CANCELLED = 2;

    private final CountDownLatch release = new CountDownLatch(1);
    private final AtomicInteger state = new AtomicInteger(WAITING);

    /** Wait for the main-thread decision. Returns true only when search was authorized. */
    public boolean awaitStart() {
        boolean interrupted = false;
        while (true) {
            try {
                release.await();
                break;
            } catch (InterruptedException ignored) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
        return state.get() == OPEN;
    }

    /** Publish completed start-callback effects and authorize the worker search. */
    public void open() {
        if (state.compareAndSet(WAITING, OPEN)) release.countDown();
    }

    /** Release the worker without allowing it to touch live search inputs. */
    public void cancel() {
        if (state.compareAndSet(WAITING, CANCELLED)) release.countDown();
    }
}
