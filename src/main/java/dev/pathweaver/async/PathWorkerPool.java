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
 * Bounded worker pool that runs A* searches off the main thread. Every executor generation owns its
 * own capacity counter, so an interrupt-ignoring task from a stopped server cannot decrement or
 * otherwise mutate the counter belonging to a restarted server.
 */
public class PathWorkerPool {
    private volatile Generation current;

    private static final long FAIL_LOG_INTERVAL_MS = 60_000L;

    private static final class Generation {
        final ThreadPoolExecutor exec;
        final AtomicInteger inFlight = new AtomicInteger();
        final int maxInFlight;
        final AtomicLong failCount = new AtomicLong();
        final AtomicLong completionFailCount = new AtomicLong();
        volatile long lastFailLogMs;

        Generation(int threads, int maxInFlight) {
            this.maxInFlight = maxInFlight;
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
    }

    /** Replace the current executor with a new capacity-isolated generation. */
    public synchronized void start(int threads, int maxInFlight) {
        Generation old = current;
        current = null;
        if (old != null) old.exec.shutdownNow();
        current = new Generation(threads, maxInFlight);
    }

    /** @return true if accepted; false if the current generation is absent, full, or rejects. */
    public boolean submit(PathRequest req) {
        Generation generation = current;
        if (generation == null) return false;
        while (true) {
            int observed = generation.inFlight.get();
            if (observed >= generation.maxInFlight) return false;
            if (generation.inFlight.compareAndSet(observed, observed + 1)) break;
        }
        if (current != generation) {
            generation.inFlight.decrementAndGet();
            return false;
        }
        try {
            generation.exec.execute(() -> {
                PathOutcome outcome;
                PathWeaverThread.enterWorker();
                try {
                    Path result = req.search().call();
                    outcome = result == null ? PathOutcome.noPath() : PathOutcome.success(result);
                } catch (Throwable t) {
                    outcome = PathOutcome.failed(t);
                    logFailure(generation, t);
                } finally {
                    PathWeaverThread.exitWorker();
                    generation.inFlight.decrementAndGet();
                }
                try {
                    req.onDone().accept(outcome);
                } catch (Throwable callbackFailure) {
                    logCompletionFailure(generation, callbackFailure);
                }
            });
            return true;
        } catch (RejectedExecutionException e) {
            generation.inFlight.decrementAndGet();
            return false;
        }
    }

    private void logFailure(Generation generation, Throwable t) {
        long n = generation.failCount.incrementAndGet();
        long now = System.currentTimeMillis();
        if (n == 1 || now - generation.lastFailLogMs >= FAIL_LOG_INTERVAL_MS) {
            generation.lastFailLogMs = now;
            try {
                reportSearchFailure(n, t);
            } catch (Throwable ignored) {
                // Reporting must never suppress the already-constructed FAILED outcome.
            }
        }
    }

    /** Test seam around a third-party logging backend; callers contain every Throwable from this method. */
    protected void reportSearchFailure(long count, Throwable failure) {
        if (count == 1L) {
            PathWeaver.LOG.warn("Async path search failed; this request is discarded and later requests "
                + "for that entity temporarily run sync. "
                + "Further failures are counted and summarised at most once/min.", failure);
        } else {
            PathWeaver.LOG.warn("Async path search failures so far: {} (latest: {}).",
                count, failure.toString());
        }
    }

    private void logCompletionFailure(Generation generation, Throwable failure) {
        generation.completionFailCount.incrementAndGet();
        try {
            PathWeaver.LOG.error("Async path completion callback failed; the result could not be "
                + "delivered to the main thread.", failure);
        } catch (Throwable ignored) {
            // The counter remains observable even if a third-party logging backend also fails.
        }
    }

    public int inFlight() {
        Generation generation = current;
        return generation == null ? 0 : generation.inFlight.get();
    }

    public long failureCount() {
        Generation generation = current;
        return generation == null ? 0L : generation.failCount.get();
    }

    public long completionFailureCount() {
        Generation generation = current;
        return generation == null ? 0L : generation.completionFailCount.get();
    }

    public synchronized void shutdown() {
        Generation generation = current;
        current = null;
        if (generation != null) generation.exec.shutdownNow();
    }
}
