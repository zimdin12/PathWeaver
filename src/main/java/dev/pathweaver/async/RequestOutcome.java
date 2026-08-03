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

    /** Dispatch setup threw after the request registered. Vanilla ran the search synchronously. */
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

    /** The worker's completion handoff threw before the result could be queued. */
    HANDOFF_FAILED("result handoff threw"),

    /** Installing the finished path threw, usually inside a foreign injection into {@code moveTo}. */
    INSTALL_FAILED("install threw"),

    /** Forgotten at a server boundary, where a late result can no longer match any live request. */
    SERVER_RESET("server reset");

    private final String description;

    RequestOutcome(String description) {
        this.description = description;
    }

    /** Human-readable phrase for the in-game diagnostic. */
    public String description() {
        return description;
    }

    /**
     * True when this outcome produced nothing usable.
     *
     * <p>{@link #NO_PATH} is excluded because the search succeeded, and {@link #POOL_SATURATED} and
     * {@link #SETUP_FAILED_PRE_DISPATCH} because no search was ever dispatched to waste.
     */
    public boolean isDiscard() {
        return this != INSTALLED && this != NO_PATH && this != POOL_SATURATED
            && this != SETUP_FAILED_PRE_DISPATCH;
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
        return this != POOL_SATURATED && this != SETUP_FAILED && this != SETUP_FAILED_PRE_DISPATCH;
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
        return this == INSTALLED || this == NO_PATH;
    }
}
