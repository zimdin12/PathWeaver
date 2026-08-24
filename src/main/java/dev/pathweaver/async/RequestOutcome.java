package dev.pathweaver.async;

/**
 * How one dispatched search ended.
 *
 * <p>There used to be a single "discarded" counter, and it meant too many things to answer any
 * question. A mob that reached its destination and stopped, a result that arrived a tick too late, a
 * mod that threw during installation, and a search that correctly proved no route exists all
 * incremented it. That last one is not a discard at all — it is a successful search whose answer
 * happens to be "nowhere to go" — and counting it as failure inflated the one number the mod
 * published about how often its work goes to waste.
 *
 * <p>The conflation also cost real engineering. An adaptive admission controller was built against
 * the ratio of discards to dispatches and reverted after it failed to converge: mobs stopping
 * normally kept the ratio above target no matter how the admission bound moved, so the signal it
 * steered on could never reach its setpoint. The counter was measuring the world, not the mod.
 *
 * <p>{@link #isDiscard()} draws the only line that matters for reporting: work that produced nothing
 * usable, as opposed to work that produced an answer.
 */
public enum RequestOutcome {
    /** A path was computed off-thread and installed on the mob. */
    INSTALLED("installed"),

    /** The search ran to completion and proved no route exists. A success with an empty answer. */
    NO_PATH("no path exists"),

    /** A materially different navigation intent replaced this request before it landed. */
    SUPERSEDED("target changed"),

    /** The mob stopped navigating. Overwhelmingly the ordinary case: it arrived, or lost interest. */
    NAVIGATION_STOPPED("mob stopped"),

    /** The result came back after it stopped being usable — too old, or the mob had moved too far. */
    ARRIVED_STALE("arrived too late"),

    /**
     * Admission refused the request, so vanilla ran it synchronously in the same tick.
     *
     * <p>Not a discard and not a dispatch: nothing was ever handed to a worker. It is counted because
     * a high rate here is the signal that the in-flight limit is the binding constraint.
     */
    POOL_SATURATED("admission refused"),

    /** Dispatch setup threw after {@code markDispatched()}. Vanilla ran the search synchronously. */
    SETUP_FAILED("dispatch setup failed"),

    /**
     * Dispatch setup threw BEFORE the request registered, so nothing was ever handed to a worker.
     *
     * <p>Separate from {@link #SETUP_FAILED} for the same reason {@link #POOL_SATURATED} is separate:
     * not a discard and not a dispatch. Counting it as a discard produced
     * {@code dispatched=0 ... discarded=41028} under a footer reading "only the amber rows are wasted
     * work", when nothing had been wasted at all — vanilla ran every one of those searches
     * synchronously in the same tick. That conflation is exactly what this enum exists to prevent,
     * and it is the number the mod page quotes.
     */
    SETUP_FAILED_PRE_DISPATCH("dispatch setup failed before admission"),

    /** The worker threw while searching. */
    SEARCH_FAILED("search threw"),

    /**
     * Dispatch declined because this family's breaker has tripped.
     *
     * <p>Counted rather than silent. A tripped family otherwise shows up as {@code dispatched} simply
     * ceasing to rise, with no row saying why -- which is exactly the shape of the setup failure that
     * 0.6.0 had to fix, where the mod did nothing indefinitely while reporting itself healthy.
     */
    BREAKER_OPEN("family switched off after worker failures"),

    /** The worker's completion handoff threw before the result could be queued. */
    HANDOFF_FAILED("result handoff threw"),

    /** Installing the finished path threw, usually inside a foreign injection into {@code moveTo}. */
    INSTALL_FAILED("install threw"),

    /** Forgotten at a server boundary, where a late result can no longer match any live request. */
    SERVER_RESET("server reset"),
    /**
     * Vanilla's {@code moveTo} declined the path we handed it.
     *
     * <p>Distinct from INSTALL_FAILED, which means something threw. This one is ordinary: moveTo
     * returns false when the path is already done or trims to zero nodes, and it has assigned
     * {@code path} by then. Reporting it as INSTALLED -- which is what ignoring the return value did
     * -- credited a successful install, cleared the entity's failure cooldown and destroyed the
     * rollback record, with no path in place.
     */
    INSTALL_REJECTED("vanilla declined the path");

    private final String description;

    RequestOutcome(String description) {
        this.description = description;
    }

    /** Human-readable phrase for the in-game diagnostic. */
    public String description() {
        return description;
    }

    /**
     * Which setup failure this is, decided in one place instead of at the throw site.
     *
     * <p>The selection used to be a ternary inside the dispatch catch, and three separate attempts to
     * pin it in bytecode were each bypassed: keying on {@code registered} instead of
     * {@code dispatchCounted}, writing {@code (registered || dispatchCounted)} so the right local is
     * still loaded last, and simply swapping the ternary arms. Every guard was a test standing beside
     * the decision rather than the decision itself.
     *
     * @param stage how far the request got. {@link DispatchStage#DISPATCHED} is NOT the same as
     *     having registered — registration precedes {@code submit} and {@code markDispatched()}
     *     follows it, so a throw from {@code submit} is registered but never dispatched
     */
    public static RequestOutcome setupFailure(DispatchStage stage) {
        return stage == DispatchStage.DISPATCHED ? SETUP_FAILED : SETUP_FAILED_PRE_DISPATCH;
    }

    /**
     * How far a dispatch got before it threw. One ordered value, not two booleans.
     *
     * <p>Two booleans let the caller write {@code (registered || dispatchCounted)}, which is
     * semantically just {@code registered} — because being dispatched implies being registered — and
     * that survived a bytecode contract written specifically to catch it, because the right local is
     * still the last one loaded. A single stage cannot express the wrong thing.
     *
     * <p>The two are genuinely different events: {@code sink.register} runs before
     * {@code pool().submit}, and {@code markDispatched()} after it, so a throw from submit is
     * {@link #REGISTERED} and never {@link #DISPATCHED}.
     */
    public enum DispatchStage {
        /** Nothing recorded yet; the failure is invisible to the sink. */
        NOT_REGISTERED,
        /** In the sink, but never handed to a worker. */
        REGISTERED,
        /** Counted by {@code markDispatched()} and therefore part of the dispatched total. */
        DISPATCHED;

        public boolean hasRegistered() {
            return this != NOT_REGISTERED;
        }
    }

    /**
     * Did this outcome leave a {@code recomputePath} caller stranded — no path, and nothing else
     * coming to give it one?
     *
     * <p>Only meaningful for {@link RequestOrigin#RECOMPUTE}. It is not "did we install": vanilla
     * would have ended in the same state for some of these, and re-arming its retry then would make
     * this mod retry where vanilla would have waited.
     *
     * <p>Exhaustive switch with no default, for the reason {@link #countsAgainstDispatched} states:
     * a new constant must not silently pick a side. Here the silent side would be "not stranded",
     * which reintroduces the twenty-tick freeze this exists to prevent.
     */
    public boolean strandsRecompute() {
        return switch (this) {
            // It has a path.
            case INSTALLED -> false;
            // A newer request for this mob is in flight and will install; re-arming would fight it.
            case SUPERSEDED -> false;
            // It stopped on purpose, or the world is going away. Nothing wants a path.
            case NAVIGATION_STOPPED, SERVER_RESET -> false;
            // The search proved there is no path. Vanilla's own createPath would have returned null
            // here too and suppressed its retry identically, so this is not a divergence to correct.
            case NO_PATH -> false;
            // Never reached a worker, or reached it and then fell through to vanilla anyway, which
            // computed synchronously and left a real path. Nothing was cancelled.
            //
            // SETUP_FAILED belongs here and an earlier version of this switch had it on the other
            // arm. Its only producer is the catch in pathweaver$asyncCreatePath, which deliberately
            // does NOT call cir.cancel() -- "fall through so vanilla computes the path
            // synchronously this tick". So the mob ends the tick holding a real path. Worse, the
            // re-arm it triggered ran from INSIDE vanilla's recomputePath, three bytecodes before
            // vanilla unconditionally overwrites both fields it writes, so the re-arm was a
            // guaranteed no-op and the forced-sync penalty was the only surviving effect: a mob
            // punished for a search that succeeded.
            case POOL_SATURATED, SETUP_FAILED_PRE_DISPATCH, SETUP_FAILED, BREAKER_OPEN -> false;
            // Dispatched, cancelled vanilla's call, and then produced nothing. These are the cases
            // where the mob is standing still and vanilla thinks it already handled it.
            case ARRIVED_STALE, SEARCH_FAILED, HANDOFF_FAILED, INSTALL_FAILED,
                 INSTALL_REJECTED -> true;
        };
    }

    public boolean isDiscard() {
        // BREAKER_OPEN joins the exemptions for the same reason POOL_SATURATED is one: nothing was
        // computed, so nothing was thrown away. Counting a refusal as waste would make the waste
        // ratio -- which drives an operator warning -- rise precisely when the mod stopped doing work.
        // INSTALL_REJECTED is a discard: the search ran, produced a path, and it was thrown away.
        return this != INSTALLED && this != NO_PATH && this != POOL_SATURATED
            && this != SETUP_FAILED_PRE_DISPATCH && this != BREAKER_OPEN;
    }

    /**
     * True when this outcome is drawn from the dispatched total, so a percentage of it means something.
     *
     * <p>Lives here rather than as a list at the reporting site. It WAS a list, and this is the second
     * constant added to it that landed in neither of the two lists that needed it: the result printed
     * a six-digit percentage — 41028 of 0 dispatched — on the one row that means the mod is doing
     * nothing for those mobs. The rule belongs to the outcome, so adding a constant forces the
     * decision instead of silently defaulting to the wrong one.
     */
    public boolean countsAgainstDispatched() {
        // An exhaustive switch with NO default. The `!=` chain it replaced claimed to "force the
        // decision" and did the opposite: a reviewer added a constant, changed nothing else, and all
        // 289 tests passed while the new row printed a percentage of a total it is not part of.
        // javac refuses to compile this when a constant is added, which is what forcing actually is.
        return switch (this) {
            // BREAKER_OPEN is refused before anything reaches a worker, so it is not drawn from the
            // dispatched total. Printing it as a percentage of dispatches would be the six-digit
            // "41028 of 0" bug again, on the row that means the mod has switched itself off.
            // SERVER_RESET is drawn from the PREVIOUS session's dispatches: onServerStarting
            // zeroes this array and then records one per leftover registration, deliberately, as the
            // only evidence leftovers existed. Counting them against this session's total printed a
            // percentage of a denominator they are not part of.
            case POOL_SATURATED, SETUP_FAILED_PRE_DISPATCH, BREAKER_OPEN, SERVER_RESET -> false;
            // SETUP_FAILED is chosen only when dispatchCounted is true, i.e. strictly after
            // markDispatched() -- so it is part of the dispatched total by construction. Putting it
            // on the false arm reproduced, inside this enum, the exact symptom the split was written
            // to remove: a row printed with no percentage while being 100% of them.
            case SETUP_FAILED, INSTALLED, NO_PATH, SUPERSEDED, NAVIGATION_STOPPED, ARRIVED_STALE,
                 SEARCH_FAILED, HANDOFF_FAILED, INSTALL_FAILED, INSTALL_REJECTED -> true;
        };
    }

    /**
     * True when this outcome is good news, i.e. safe to print green beside {@code installed}.
     *
     * <p>Not simply {@code !isDiscard()}: admission refusal and a pre-admission setup failure are not
     * discards — nothing was computed and thrown away — but neither is a success, and printing them
     * green under a footer reading "only the amber rows are wasted work" tells an operator that the
     * mod failing to run is a good outcome.
     */
    public boolean isGoodNews() {
        return switch (this) {
            case INSTALLED, NO_PATH -> true;
            case POOL_SATURATED, SETUP_FAILED, SETUP_FAILED_PRE_DISPATCH, SUPERSEDED,
                 NAVIGATION_STOPPED, ARRIVED_STALE, SEARCH_FAILED, HANDOFF_FAILED, INSTALL_FAILED,
                 INSTALL_REJECTED, SERVER_RESET, BREAKER_OPEN -> false;
        };
    }
}
