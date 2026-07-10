package dev.pathweaver.async;

import net.minecraft.world.level.pathfinder.Path;

import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Collects completed paths from worker threads and installs them on the main thread. Staleness is
 * decided by the {@link InstallSink} (which owns the live entity); the installer only guarantees
 * thread-safe hand-off and one-shot delivery. A {@code null} path (worker failed) is discarded so
 * the mob re-requests via the normal sync route.
 */
public class ResultInstaller {

    public interface InstallSink {
        boolean isStale(int entityId, long dispatchTick, double x, double y, double z);
        void install(int entityId, Path path);
        void discard(int entityId);

        /**
         * FIX 4: a {@code null} result means the worker search FAILED (threw), as opposed to a stale
         * but valid result. Distinguished so the sink can flag the entity to run synchronously next
         * tick instead of re-dispatching a deterministically-failing async search forever. Defaults to
         * {@link #discard} for callers that don't care (keeps older sinks source-compatible).
         */
        default void failed(int entityId) { discard(entityId); }
    }

    private record Result(int entityId, long dispatchTick, Path path, double x, double y, double z) {}

    private final ConcurrentLinkedQueue<Result> queue = new ConcurrentLinkedQueue<>();

    /** Called from a worker thread. */
    public void enqueue(int entityId, long dispatchTick, Path path, double x, double y, double z) {
        queue.add(new Result(entityId, dispatchTick, path, x, y, z));
    }

    /** Called on the main thread at tick start; delivers each queued result exactly once. */
    public void drain(InstallSink sink) {
        Result r;
        while ((r = queue.poll()) != null) {
            if (r.path() == null) {
                sink.failed(r.entityId());
            } else if (sink.isStale(r.entityId(), r.dispatchTick(), r.x(), r.y(), r.z())) {
                sink.discard(r.entityId());
            } else {
                sink.install(r.entityId(), r.path());
            }
        }
    }

    public int pending() { return queue.size(); }

    /** FIX 5: drop any results left over from a previous server session on start/stop. */
    public void clear() { queue.clear(); }
}
