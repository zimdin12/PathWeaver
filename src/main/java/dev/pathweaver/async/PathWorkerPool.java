package dev.pathweaver.async;

import dev.pathweaver.PathWeaver;
import net.minecraft.world.level.pathfinder.Path;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bounded worker pool that runs A* searches off the main thread. Threads are daemon and one notch
 * below normal priority. Saturation/rejection returns false so the current caller can continue sync.
 * A task exception is delivered as {@code null}; it is not recomputed here and instead causes the
 * install sink to put later requests for that entity into a synchronous cooldown.
 */
public class PathWorkerPool {
    private ThreadPoolExecutor exec;
    private final AtomicInteger inFlight = new AtomicInteger();
    private volatile int maxInFlight;

    // FIX 4b: rate-limit the failure log so a deterministically-failing search can't spam stack traces.
    private final AtomicLong failCount = new AtomicLong();
    private volatile long lastFailLogMs = 0L;
    private static final long FAIL_LOG_INTERVAL_MS = 60_000L;

    /**
     * Start/restart the executor and reset the shared counter. This prevents dropped queued tasks from
     * permanently consuming capacity, but 0.1.1 has no epoch: an interrupt-ignoring old task may still
     * decrement this counter after restart. The v0.2 lifecycle protocol must isolate generations.
     */
    public synchronized void start(int threads, int maxInFlight) {
        if (exec != null && !exec.isShutdown()) {
            exec.shutdownNow(); // start() called twice without stop(): replace cleanly.
        }
        this.maxInFlight = maxInFlight;
        this.inFlight.set(0);
        this.failCount.set(0);
        this.lastFailLogMs = 0L;
        this.exec = new ThreadPoolExecutor(threads, threads, 30, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(),
            r -> {
                Thread t = new Thread(r, "PathWeaver-Worker");
                t.setDaemon(true);
                t.setPriority(Thread.NORM_PRIORITY - 1);
                return t;
            });
        this.exec.allowCoreThreadTimeOut(true);
    }

    /** @return true if accepted; false if the in-flight cap is hit or the pool rejects (caller does sync). */
    public boolean submit(PathRequest req) {
        if (exec == null) return false;
        if (inFlight.get() >= maxInFlight) return false;
        inFlight.incrementAndGet();
        try {
            exec.execute(() -> {
                Path result = null;
                PathWeaverThread.enterWorker(); // FIX 1a: cover ALL worker execution with the worker flag.
                try {
                    result = req.search().call();
                } catch (Throwable t) {
                    logFailure(t);
                    result = null;
                } finally {
                    PathWeaverThread.exitWorker();
                    inFlight.decrementAndGet();
                    try {
                        req.onDone().accept(result);
                    } catch (Throwable ignored) {
                        // onDone only enqueues; never let it escape.
                    }
                }
            });
            return true;
        } catch (RejectedExecutionException e) {
            inFlight.decrementAndGet();
            return false;
        }
    }

    private void logFailure(Throwable t) {
        long n = failCount.incrementAndGet();
        long now = System.currentTimeMillis();
        if (n == 1) {
            PathWeaver.LOG.warn("Async path search failed; this request is discarded and later requests "
                + "for that entity temporarily run sync. "
                + "Further failures are counted and summarised at most once/min.", t);
            lastFailLogMs = now;
        } else if (now - lastFailLogMs >= FAIL_LOG_INTERVAL_MS) {
            PathWeaver.LOG.warn("Async path search failures so far: {} (latest: {}).", n, t.toString());
            lastFailLogMs = now;
        }
    }

    public int inFlight() { return inFlight.get(); }

    public long failureCount() { return failCount.get(); }

    public synchronized void shutdown() {
        if (exec != null) {
            exec.shutdownNow();
            exec = null;
        }
        // Releases capacity held by dropped queued tasks. Old running tasks are not epoch-isolated yet.
        inFlight.set(0);
    }
}
