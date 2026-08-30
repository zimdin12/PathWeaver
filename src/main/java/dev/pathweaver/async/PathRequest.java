package dev.pathweaver.async;

import net.minecraft.world.level.pathfinder.Path;

import java.util.concurrent.Callable;
import java.util.function.Consumer;

/**
 * An immutable dispatch envelope. In 0.2.0 {@code search} still reads a live-backed region view and
 * live mob inputs; the envelope itself being immutable does not make those inputs snapshots.
 * {@code onDone} is invoked on the worker thread solely to enqueue the result — it must NEVER touch
 * the live world. Identity fields let the main-thread installer match the result to its entity and
 * decide staleness.
 *
 * <p>{@code evaluatorClass} exists so a search that throws can be attributed to a movement family on
 * the worker, at the only point where every failure is visible. The obvious alternative -- looking the
 * family up from the install sink when the failure drains -- silently drops any failure whose
 * registration has already been superseded, and {@code supersede()} runs on every entry to
 * {@code recomputePath}, which is what vanilla calls on block-change invalidation. That is precisely
 * when a concurrent read throws, so the blind spot would have been correlated with the hazard.
 */
public record PathRequest(RequestKey key, long dispatchTick, Callable<Path> search,
                          Consumer<PathOutcome> onDone,
                          Consumer<RequestKey> onDeliveryFailure,
                          java.util.function.BooleanSupplier stillWanted,
                          // LAST, deliberately. A bytecode contract asserts that the value handed to
                          // this constructor immediately before it runs is this.nodeEvaluator's own
                          // class -- swapping that receiver silently disables failure attribution and
                          // the breaker while every other test stays green. Adding an argument after
                          // it made the contract watch the wrong operand, and relaxing the contract to
                          // compensate let the real mutation survive, because an earlier
                          // nodeEvaluator.getClass() in the same method satisfied the weaker rule.
                          Class<?> evaluatorClass) {

    /**
     * Asked on the worker, immediately before the search runs.
     *
     * <p>A request can sit in the queue for several ticks. By the time a worker reaches it the mob
     * may have stopped, or a newer request may have replaced it, and computing an A* nobody will
     * install is CPU spent for nothing -- which on a machine without a spare core comes out of the
     * budget this mod exists to protect. Every discard measured on the reference pack was of exactly
     * this shape: 88 of 88 were "mob stopped".
     */
    /** Convenience for tests/callers that do not own a main-thread terminal queue. */
    public PathRequest(RequestKey key, long dispatchTick, Callable<Path> search,
                       Consumer<PathOutcome> onDone) {
        this(key, dispatchTick, search, onDone, ignored -> { }, () -> true, null);
    }

    /** Convenience for callers that own a terminal queue but have no evaluator to attribute. */
    public PathRequest(RequestKey key, long dispatchTick, Callable<Path> search,
                       Consumer<PathOutcome> onDone, Consumer<RequestKey> onDeliveryFailure) {
        this(key, dispatchTick, search, onDone, onDeliveryFailure, () -> true, null);
    }
}
