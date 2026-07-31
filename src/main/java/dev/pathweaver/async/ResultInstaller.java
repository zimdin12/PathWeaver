package dev.pathweaver.async;

import net.minecraft.world.level.pathfinder.Path;

import java.util.concurrent.ConcurrentLinkedQueue;

/** Thread-safe worker-to-main-thread handoff with exact request identity and tagged terminal outcomes. */
public class ResultInstaller {

    public interface InstallSink {
        boolean isStale(RequestKey key, long dispatchTick, double x, double y, double z);
        void install(RequestKey key, Path path);

        /**
         * End this request without installing anything.
         *
         * <p>The reason is required rather than defaulted. Every caller knows exactly why it is
         * discarding, and the one number this used to produce was unreadable precisely because that
         * knowledge was thrown away at the call site.
         */
        void discard(RequestKey key, RequestOutcome reason);

        default void noPath(RequestKey key) { discard(key, RequestOutcome.NO_PATH); }
        default void failed(RequestKey key, Throwable failure) {
            discard(key, RequestOutcome.SEARCH_FAILED);
        }
    }

    private record Result(RequestKey key, long dispatchTick, PathOutcome outcome,
                          double x, double y, double z, boolean discardOnly) { }

    private final ConcurrentLinkedQueue<Result> queue = new ConcurrentLinkedQueue<>();

    /** Called from a worker thread. */
    public void enqueue(RequestKey key, long dispatchTick, PathOutcome outcome,
                        double x, double y, double z) {
        queue.add(new Result(key, dispatchTick, outcome, x, y, z, false));
    }

    /**
     * Called from a worker when its normal completion consumer threw. The exact-key discard is
     * queued rather than executed here so navigation state and callbacks remain main-thread-owned.
     */
    public void enqueueDiscard(RequestKey key) {
        queue.add(new Result(key, 0L, null, 0.0, 0.0, 0.0, true));
    }

    /** Called on the main thread; delivers each queued result exactly once. */
    public void drain(InstallSink sink) {
        Result result;
        while ((result = queue.poll()) != null) {
            if (result.discardOnly()) {
                sink.discard(result.key(), RequestOutcome.HANDOFF_FAILED);
                continue;
            }
            switch (result.outcome().status()) {
                case NO_PATH -> sink.noPath(result.key());
                case FAILED -> sink.failed(result.key(), result.outcome().failure());
                case SUCCESS -> {
                    if (sink.isStale(result.key(), result.dispatchTick(),
                            result.x(), result.y(), result.z())) {
                        sink.discard(result.key(), RequestOutcome.ARRIVED_STALE);
                    } else {
                        sink.install(result.key(), result.outcome().path());
                    }
                }
            }
        }
    }

    public int pending() { return queue.size(); }
    public void clear() { queue.clear(); }
}
