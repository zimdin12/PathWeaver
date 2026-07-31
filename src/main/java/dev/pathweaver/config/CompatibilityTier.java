package dev.pathweaver.config;

import me.shedaniel.clothconfig2.gui.entries.SelectionListEntry;

/**
 * How much risk the server owner is willing to accept from mods that modify pathfinding.
 *
 * <p>PathWeaver refuses to run a search off-thread when another mod has modified the code that
 * search executes, because it cannot verify what that code does on a worker. That rule is why the
 * mod is inert on most modpacks. This setting decides how much of that refusal to keep.
 *
 * <p>There were three tiers. The strictest honoured only structural proofs, and the exemption
 * covering Fabric API's own interaction module rests on a bounded call sample rather than a proof —
 * so it denied every install that contained Fabric API, which this mod requires. It could not do
 * anything on any pack that has ever existed, and a setting that is inert everywhere is not a
 * safety option, it is noise. What remains is an honest binary: run where there is evidence, or run
 * regardless.
 */
public enum CompatibilityTier implements SelectionListEntry.Translatable {
    /**
     * Also allow mods whose bytecode has been audited to perform no shared-state writes on the
     * search path, but which do add reads a worker cannot be proven to see consistently.
     *
     * <p>The honest trade: these cannot corrupt the world from a worker, because they write
     * nothing a worker reaches. They can still return a worse or stale path when the world changes
     * mid-search, and can hit a concurrent-modification exception. That is contained: the affected
     * search is discarded rather than installed, and that mob is forced synchronous for a short
     * cooldown, so the fallback is on later requests rather than a synchronous retry of this one.
     * Lithium is the mod that matters here — it ships in most performance packs, and without this
     * audit its presence alone would keep PathWeaver switched off.
     *
     * <p>Pinned to exact artifact bytes; a different build of an audited mod is not audited.
     */
    AUDITED,

    /**
     * Ignore the compatibility scan entirely and run off-thread regardless of what modified
     * pathfinding.
     *
     * <p>Named for what it does rather than for everything it might be assumed to do. It waives the
     * compatibility scan and the evaluator allowlist for third-party subclasses, which is every
     * question about <em>other mods'</em> code. It does not waive the two vanilla evaluators that
     * write to the mob during a search — flying and amphibious stay synchronous here too, because
     * that is a race with zero mods installed and no setting can make it safe. Calling this tier
     * "all" implied a completeness it does not have.
     *
     * <p>This runs unaudited third-party code on a worker thread, which is the exact thing the
     * scan exists to prevent. Nothing here has been proven about thread-safety, and a failure mode
     * is not limited to a bad path — it can be a crash or a corrupted world. For experimentation
     * on worlds you can afford to lose. Keep backups.
     */
    UNSAFE;

    /**
     * True when audited-but-not-proven-inert exemptions may be honoured, which both tiers do.
     *
     * <p>Kept rather than deleted: the value is frozen at scan time and read through
     * {@code ActiveCompatibilityPolicy}, which answers false until the scan publishes a tier. That
     * is the fail-closed property, and it belongs to the freezing, not to any particular tier.
     */
    public boolean allowsAudited() {
        return true;
    }

    /** True when the compatibility scan is bypassed outright. */
    public boolean bypassesScan() {
        return this == UNSAFE;
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
