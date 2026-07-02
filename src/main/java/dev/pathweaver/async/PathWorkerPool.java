package dev.pathweaver.async;

import dev.pathweaver.PathWeaver;
import net.minecraft.world.level.pathfinder.Path;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bounded worker pool that runs A* searches off the main thread. Threads are daemon and one notch
 * below normal priority so they never starve the render/main threads. On saturation ({@code submit}
 * returns false) or task exception (result delivered as {@code null}), the caller falls back to sync.
 */
public class PathWorkerPool {
    private ThreadPoolExecutor exec;
    private final AtomicInteger inFlight = new AtomicInteger();
    private volatile int maxInFlight;

    public void start(int threads, int maxInFlight) {
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

    /** @return true if accepted; false if the in-flight cap is hit or the pool rejects (caller does sync). */
    public boolean submit(PathRequest req) {
        if (exec == null) return false;
        if (inFlight.get() >= maxInFlight) return false;
        inFlight.incrementAndGet();
        try {
            exec.execute(() -> {
                Path result = null;
                try {
                    result = req.search().call();
                } catch (Throwable t) {
                    PathWeaver.LOG.warn("Async path search failed; falling back to sync.", t);
                    result = null;
                } finally {
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

    public int inFlight() { return inFlight.get(); }

    public void shutdown() {
        if (exec != null) exec.shutdownNow();
    }
}
