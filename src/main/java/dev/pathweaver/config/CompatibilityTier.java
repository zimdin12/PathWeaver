package dev.pathweaver.config;

import me.shedaniel.clothconfig2.gui.entries.SelectionListEntry;

/**
 * How much risk the server owner is willing to accept from mods that modify pathfinding.
 *
 * <p>PathWeaver refuses to run a search off-thread when another mod has modified the code that
 * search executes, because it cannot verify what that code does on a worker. That rule is why the
 * mod is inert on most modpacks. This setting decides how much of that refusal to keep, and the
 * ordering is deliberate: each tier is a strict superset of the one before it.
 *
 * <p>The tiers are not "how likely is it to break" — they are "what kind of evidence exists".
 */
public enum CompatibilityTier implements SelectionListEntry.Translatable {
    /**
     * Only run off-thread where a worker provably cannot observe the foreign change at all.
     *
     * <p>Every exemption at this tier rests on a structural proof: the modified method is not
     * reachable from the worker's entry point, or the modification is inert unless something is
     * registered that we check for and re-check at install time. Verified against exact artifact
     * bytes, so an unexpected build denies rather than assuming.
     */
    STRICT,

    /**
     * Also allow mods whose bytecode has been audited to perform no shared-state writes on the
     * search path, but which do add reads a worker cannot be proven to see consistently.
     *
     * <p>The honest trade: these cannot corrupt the world from a worker, because they write
     * nothing a worker reaches. They can still return a worse or stale path when the world changes
     * mid-search, and can hit a concurrent-modification exception, which is contained as a failed
     * search that falls back to synchronous pathfinding. Lithium is the mod that matters here — it
     * ships in most performance packs, and at {@link #STRICT} its presence alone keeps PathWeaver
     * switched off.
     *
     * <p>Pinned to exact artifact bytes; a different build of an audited mod is not audited.
     */
    AUDITED,

    /**
     * Ignore the compatibility scan entirely and run off-thread regardless of what modified
     * pathfinding.
     *
     * <p>This runs unaudited third-party code on a worker thread, which is the exact thing the
     * scan exists to prevent. Nothing here has been proven about thread-safety, and a failure mode
     * is not limited to a bad path — it can be a crash or a corrupted world. For experimentation
     * on worlds you can afford to lose. Keep backups.
     */
    ALL;

    /** True when audited-but-not-proven-inert exemptions may be honoured. */
    public boolean allowsAudited() {
        return this != STRICT;
    }

    /** True when the compatibility scan is bypassed outright. */
    public boolean bypassesScan() {
        return this == ALL;
    }

    /**
     * Translation key for the settings screen.
     *
     * <p>Cloth's default enum label provider calls {@code Component.translatable(toString())} unless
     * the constant implements this interface, so without it the screen would render the bare
     * constant name and the {@code option.compatibilityTier.*} entries in the language file would
     * never be read. Supplying the key here keeps {@link #toString()} untouched, which matters
     * because the tier is written into log lines and diagnostics where a translation key would be
     * unreadable.
     */
    @Override
    public String getKey() {
        return TRANSLATION_PREFIX + name();
    }

    /** Shared with the ModMenu contract test so the key format cannot drift from the language file. */
    public static final String TRANSLATION_PREFIX =
        "text.autoconfig.pathweaver.option.compatibilityTier.";
}
