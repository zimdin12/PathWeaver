package dev.pathweaver.config;


/**
 * What the shared result cache is allowed to do with a search another mob already ran.
 *
 * <p>Lives in the config package, next to {@link CompatibilityTier}, for the same reason: it
 * implements a Cloth GUI interface, so anything that names it drags the settings-screen classes in
 * behind it. The dispatch interceptor is a mixin and must not do that, which is why
 * {@link PathWeaverConfig#resultCacheServes()} exists as a primitive.
 */
public enum PathCacheMode {
    /** Not consulted, not filled, no key built. Costs nothing and measures nothing. */
    OFF,

    /**
     * Filled and consulted, but a hit is counted and then discarded; the search runs anyway.
     *
     * <p>The shipped default, because whether sharing pays is an empirical question and this project
     * has not answered it yet. A hit needs a second search from the same block, for the same target,
     * by a mob with the same size, evaluator settings and terrain costs, inside the age limit, with
     * no block changed along the route. How often that happens depends entirely on the pack: a
     * village full of villagers standing at work sites is a different population from a plains full
     * of wandering cows.
     *
     * <p>So this mode measures it on the machine that matters. {@code /pathweaver status} reports
     * what {@link #SERVE} would have returned and, separately, what it would have returned if the
     * mob's position were compared by block rather than exactly — the one loosening of the key worth
     * knowing about before anyone designs it. Nothing a mob does changes.
     *
     * <p>Cost is one key and one map lookup per dispatched search.
     */
    SHADOW,

    /** Hits are served: the mob gets its own copy of the earlier route and no search runs. */
    SERVE;

    /**
     * The translation key for this constant on the settings screen.
     *
     * <p>No longer an override of a Cloth interface. That interface was on the enum itself, so
     * initialising PathWeaverConfig, which has a field of this type, resolved a Cloth GUI class and
     * threw NoClassDefFoundError on any server without Cloth installed. The mod could not start, and
     * no unit test saw it because the test classpath has Cloth on it. Only booting a real server
     * without the library found it.
     *
     * <p>ClothScreen calls this when it builds an enum selector; nothing else needs it.
     */
    public String getKey() {
        return TRANSLATION_PREFIX + name();
    }

    /** Shared with the ModMenu contract test so the key format cannot drift from the language file. */
    public static final String TRANSLATION_PREFIX =
        "text.autoconfig.pathweaver.option.resultCacheMode.";
}
