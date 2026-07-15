package dev.pathweaver.async;

import net.minecraft.world.level.pathfinder.Path;

import java.util.concurrent.ConcurrentLinkedQueue;

/** Thread-safe worker-to-main-thread handoff with exact request identity. */
public class ResultInstaller {

    public interface InstallSink {
        boolean isStale(RequestKey key, long dispatchTick, double x, double y, double z);
        void install(RequestKey key, Path path);
        void discard(RequestKey key);
        default void failed(RequestKey key) { discard(key); }
    }

    private record Result(RequestKey key, long dispatchTick, Path path,
                          double x, double y, double z) { }

    private final ConcurrentLinkedQueue<Result> queue = new ConcurrentLinkedQueue<>();

    /** Called from a worker thread. */
    public void enqueue(RequestKey key, long dispatchTick, Path path,
                        double x, double y, double z) {
        queue.add(new Result(key, dispatchTick, path, x, y, z));
    }

    /** Called on the main thread; delivers each queued result exactly once. */
    public void drain(InstallSink sink) {
        Result result;
        while ((result = queue.poll()) != null) {
            if (result.path() == null) {
                sink.failed(result.key());
            } else if (sink.isStale(result.key(), result.dispatchTick(),
                    result.x(), result.y(), result.z())) {
                sink.discard(result.key());
            } else {
                sink.install(result.key(), result.path());
            }
        }
    }

    public int pending() { return queue.size(); }
    public void clear() { queue.clear(); }
}
