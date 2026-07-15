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
                Path result = null;
                PathWeaverThread.enterWorker();
                try {
                    result = req.search().call();
                } catch (Throwable t) {
                    logFailure(generation, t);
                } finally {
                    PathWeaverThread.exitWorker();
                    generation.inFlight.decrementAndGet();
                    try {
                        req.onDone().accept(result);
                    } catch (Throwable ignored) {
                        // Completion callbacks only enqueue. Callback reporting is a separate v0.2 slice.
                    }
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
        if (n == 1) {
            PathWeaver.LOG.warn("Async path search failed; this request is discarded and later requests "
                + "for that entity temporarily run sync. "
                + "Further failures are counted and summarised at most once/min.", t);
            generation.lastFailLogMs = now;
        } else if (now - generation.lastFailLogMs >= FAIL_LOG_INTERVAL_MS) {
            PathWeaver.LOG.warn("Async path search failures so far: {} (latest: {}).", n, t.toString());
            generation.lastFailLogMs = now;
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

    public synchronized void shutdown() {
        Generation generation = current;
        current = null;
        if (generation != null) generation.exec.shutdownNow();
    }
}
