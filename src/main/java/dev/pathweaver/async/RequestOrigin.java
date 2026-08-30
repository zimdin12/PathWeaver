package dev.pathweaver.async;

/**
 * Which vanilla call site asked for this search, because reconciliation must differ between them.
 *
 * <p>A {@code moveTo} caller still holds whatever path it had; cancelling that call and answering a
 * tick later costs nothing it was relying on. {@code recomputePath} is not like that. Vanilla nulls
 * {@code path} immediately before calling {@code createPath}, then — believing the call answered —
 * stamps {@code timeLastRecompute} and clears {@code hasDelayedRecomputation}. Both of its retry
 * routes are now shut for twenty ticks on a mob that has no path at all.
 *
 * <p>So the same terminal outcome means two different things depending on where the request came
 * from, and only the origin can tell them apart.
 */
public enum RequestOrigin {
    /** A {@code moveTo} overload. The caller's existing path is untouched by the dispatch. */
    MOVE_TO,
    /** {@code recomputePath}. The path is already null and vanilla's retry is already suppressed. */
    RECOMPUTE,
    /**
     * {@code MoveToTargetSink.tryComputePath}, the villager-brain movement sink.
     *
     * <p>Unlike the other two this one must NOT install. The behaviour installs the path itself in
     * {@code start()} via {@code moveTo(Path, double)}, so the result parks until the sink next asks
     * and is handed to vanilla's own reachability and memory logic unchanged.
     */
    BRAIN_SINK
}
