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

        /**
         * Release the evaluator this request prepared, now that its worker has provably finished.
         *
         * <p>The pool queues a result only after the search callable returns, so draining one is the
         * earliest point at which the main thread may touch that evaluator again.
         */
        void runEpilogue(RequestKey key);

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
            try {
                deliver(sink, result);
            } finally {
                // Unconditional: a request whose delivery threw still owes its mob the search costs
                // the prologue took, and leaving them applied is permanent.
                sink.runEpilogue(result.key());
            }
        }
    }

    private void deliver(InstallSink sink, Result result) {
            if (result.discardOnly()) {
                sink.discard(result.key(), RequestOutcome.HANDOFF_FAILED);
                return;
            }
            switch (result.outcome().status()) {
                case NO_PATH -> sink.noPath(result.key());
                // Recorded directly, NOT through discard(). A cancellation happens precisely
                // when this key is no longer registered, and discard() requires a live
                // registration under that exact key -- so routing it there recorded nothing at
                // all, and the row an operator was told would grow was provably always zero.
                // There is also nothing to unwind: no registration means no optimistic target and
                // no rollback owed. The epilogue still runs, because drain() honours it in a
                // finally regardless of outcome.
                case CANCELLED -> dev.pathweaver.PathWeaverRuntime.get()
                    .markOutcome(RequestOutcome.CANCELLED_BEFORE_START);
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

    public int pending() { return queue.size(); }
    public void clear() { queue.clear(); }
}
