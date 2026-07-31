package dev.pathweaver.async;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One-shot acceptance barrier: an accepted worker cannot read live search inputs until the main
 * thread has finished the search's prologue. Cancellation releases the worker without authorizing a
 * search.
 *
 * <p>The prologue is the evaluator's own {@code prepare(region, mob)}, run on the main thread since
 * 0.4.0, so this barrier now carries more than an ordering preference: the worker's search reads
 * evaluator state that another thread wrote. {@link CountDownLatch} supplies the happens-before edge
 * — everything the main thread did before {@link #open()} is visible to the worker once
 * {@link #awaitStart()} returns — which is why the release must stay a latch and not become a
 * volatile flag or a spin on {@link #state}.
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
