package dev.pathweaver.gate;

import dev.pathweaver.async.EvaluatorCloner;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import net.minecraft.world.level.pathfinder.AmphibiousNodeEvaluator;
import net.minecraft.world.level.pathfinder.FlyNodeEvaluator;
import net.minecraft.world.level.pathfinder.SwimNodeEvaluator;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;

/**
 * Decides whether a mob's A* search may run off-thread.
 *
 * <p>The rule is an exact-class allowlist of vanilla evaluators, never {@code instanceof}: a mod
 * evaluator that extends one of them — stormiespiders' {@code AdvancedWalkNodeProcessor} is the live
 * example — reads live world state during node evaluation, so {@code instanceof} would wrongly pass
 * it. Default-deny. The worker still consumes a read-only region view backed by live chunks and live
 * mob inputs, so async remains experimental.
 *
 * <p>The allowlist covers every concrete vanilla evaluator as of 26.1.2. The flying and amphibious
 * ones were excluded until 0.4.0, and the reason they no longer are is worth recording, because the
 * old exclusion described the problem inaccurately. It was never the search: the amphibious
 * evaluator's five {@code Mob.setPathfindingMalus} writes are all in {@code prepare} and {@code done},
 * and the flying evaluator's single {@code Mob.getRandom()} read is in start-node selection. The A*
 * loop between them only reads. {@code PathFinderMixin} now runs the prologue and epilogue on the
 * main thread and {@code FlyNodeEvaluatorMixin} gives a worker its own randomness, which removes the
 * hazard at its source rather than routing around it. The frog's and creaking's evaluators come along
 * with it: verified on 26.1.2, they override only {@code getStart} and {@code getPathType} and
 * inherit their prologue from the classes above.
 *
 * <p>The frog's touches the mob solely through {@code getBoundingBox}. The creaking's does not, and
 * this used to claim otherwise: {@code Creaking$HomeNodeEvaluator.getPathType} calls
 * {@code getHomePos()}, which reads {@code SynchedEntityData} through a plain non-volatile field
 * while the main thread may write it. It is admitted anyway, deliberately — the worst case is a
 * stale home position, so the 1024-block cutoff is measured against a slightly old anchor, which
 * changes no path the mob could not also have taken a tick earlier. That is a judgement, not the
 * absence of a read, and stating it as the latter was wrong.
 *
 * <p>Two package-private evaluators are declared inside their mobs and so cannot be named in source.
 * They are resolved by name and simply absent if a future version moves them — the allowlist shrinks,
 * which fails in the safe direction.
 */
public final class SafetyGate {
    private static final String FROG_EVALUATOR =
        "net.minecraft.world.entity.animal.frog.Frog$FrogNodeEvaluator";
    private static final String CREAKING_EVALUATOR =
        "net.minecraft.world.entity.monster.creaking.Creaking$HomeNodeEvaluator";

    private static final Set<Class<?>> ALLOWED = allowlist();

    private static Set<Class<?>> allowlist() {
        // List.of, not Set.of, so iteration order is the declaration order rather than salted per
        // JVM -- two log excerpts from the same pack used to list the families differently. But
        // List.of does not reject duplicates the way Set.of did, so the fail-fast is restored
        // explicitly. Compare the LIST against the SET: an earlier version of this check read the
        // set size after LinkedHashSet had already deduplicated, so it could never see a duplicate.
        java.util.List<Class<?>> declared = java.util.List.of(
            WalkNodeEvaluator.class,
            SwimNodeEvaluator.class,
            FlyNodeEvaluator.class,
            AmphibiousNodeEvaluator.class);
        Set<Class<?>> allowed = new LinkedHashSet<>(declared);
        if (allowed.size() != declared.size()) {
            throw new AssertionError("duplicate entry in the evaluator allowlist: " + declared);
        }
        addIfPresent(allowed, FROG_EVALUATOR);
        addIfPresent(allowed, CREAKING_EVALUATOR);
        return Collections.unmodifiableSet(allowed);
    }

    private static void addIfPresent(Set<Class<?>> allowed, String className) {
        try {
            allowed.add(Class.forName(className, false, SafetyGate.class.getClassLoader()));
        } catch (ClassNotFoundException | LinkageError movedOrRenamed) {
            // Absent means that mob keeps pathing synchronously, which is the safe direction.
        }
    }

    /** Every evaluator class this build knows how to run off-thread, denials aside. */
    public static Set<Class<?>> allowlisted() {
        return ALLOWED;
    }

    /**
     * Allowlisted vanilla classes that another jar mixes into (populated at startup by
     * {@link ForeignMixinScanner}). A mixin keeps the class identity {@code WalkNodeEvaluator},
     * so the allowlist alone cannot see it — this set is the second line of defence.
     */
    public static final Set<Class<?>> deniedBySafety =
        Collections.synchronizedSet(new HashSet<>(ALLOWED));

    /**
     * Families switched off after a search threw on a worker, as opposed to after the scan objected.
     *
     * <p>A SEPARATE set, and that is the whole point. {@link #deniedBySafety} is cleared wholesale by
     * the unsafe tier at startup ({@code ForeignMixinScanner} calls {@code replaceDenials(Set.of())}),
     * and a runtime trip must not be waivable by any tier — it fires precisely when the prediction the
     * tier waived turned out to be wrong. Keeping them apart also lets every diagnostic say "denied by
     * the scan" and "switched off after a failure" as different sentences, which is the difference
     * between naming a cause and inventing one.
     *
     * <p>Copy-on-write behind a {@code volatile} rather than a synchronized set, because
     * {@link #isDenied} runs on the hot dispatch path once per repath per mob. A trip logs, logging
     * calls a third-party appender, and third-party code on a lock the dispatch path takes is this
     * mod's entire threat model pointed at itself.
     */
    private static volatile Set<Class<?>> deniedByRuntimeFailure = Set.of();

    /** Guards the read-modify-write of the copy-on-write set. Never held on the dispatch path. */
    private static final Object RUNTIME_TRIP_LOCK = new Object();

    private SafetyGate() {}

    /**
     * Switch a family off for the rest of the session after a worker search threw.
     *
     * <p>Idempotent. Returns true only for the transition, so the caller can log once without owning
     * a second piece of state that could disagree with this one.
     */
    public static boolean tripRuntimeFailure(Class<?> family) {
        synchronized (RUNTIME_TRIP_LOCK) {
            if (deniedByRuntimeFailure.contains(family)) return false;
            Set<Class<?>> next = new HashSet<>(deniedByRuntimeFailure);
            next.add(family);
            deniedByRuntimeFailure = Set.copyOf(next);
            return true;
        }
    }

    /** Families currently switched off by a runtime failure, for the diagnostics. */
    public static Set<Class<?>> runtimeFailureDenials() {
        return deniedByRuntimeFailure;
    }

    /**
     * True when this evaluator is refused because a search threw, not because the scan objected.
     *
     * <p>The empty check is not a micro-optimisation reflex; it is on the dispatch path, once per
     * repath per mob, and the set is empty on every healthy server for the entire session. Iterating
     * an empty immutable set still allocates an iterator, and at ~95,000 dispatches a benchmark run
     * that is not free. A feature that should never fire must cost nothing when it does not.
     */
    public static boolean isDeniedByRuntimeFailure(Class<?> evaluatorClass) {
        Set<Class<?>> denied = deniedByRuntimeFailure;
        if (denied.isEmpty()) return false;
        for (Class<?> family : denied) {
            if (family.isAssignableFrom(evaluatorClass)) return true;
        }
        return false;
    }

    /**
     * Clear runtime trips. Called when a server starts, NOT when the JVM does.
     *
     * <p>{@code SafetyGate} is a per-JVM static and a singleplayer client starts many servers in one
     * JVM. {@code EntityInstallSink.clear()} already re-arms its one-shot log flags for exactly this
     * reason — a failure logged in world A silenced the first failure of every later world — and a
     * trip that survived into world B would be worse still: a permanently inert mod with no log line,
     * because the one-shot had already burned.
     */
    public static void resetRuntimeFailureDenials() {
        synchronized (RUNTIME_TRIP_LOCK) {
            deniedByRuntimeFailure = Set.of();
        }
    }

    /**
     * The MOST GENERAL allowlisted family this evaluator is derived from, or null if none.
     *
     * <p>Deliberately the root rather than the exact class, and this was written the other way first
     * and the test caught it. Fly, Amphibious, the frog's and the creaking's evaluators are each
     * allowlisted in their own right AND each extend {@code WalkNodeEvaluator}, so "exact match
     * first" files a failure against four separate keys for what is one piece of shared code, and the
     * configured threshold is silently multiplied by four. It also disagrees with {@link #isDenied},
     * which already closes over subclasses — counting and denying must use the same closure or the
     * mechanism trips on a set it did not measure.
     *
     * <p><b>The trade, stated rather than assumed.</b> A bug genuinely confined to the flying
     * evaluator's own code is attributed to Walk and switches off all five land families rather than
     * one. That is the right direction for this hazard: a concurrent-read failure lives in the shared
     * block-reading path, not in a family's own neighbour enumeration, so the over-denial is rare and
     * the under-counting it replaces would have meant never tripping at all.
     */
    public static Class<?> allowlistedFamilyOf(Class<?> evaluatorClass) {
        return allowlistedFamilyOf(evaluatorClass, ALLOWED);
    }

    /**
     * The candidate set is a parameter so the RULE can be tested rather than the declaration order.
     *
     * <p>Reducing this to "first match wins" survived every test, because {@code WalkNodeEvaluator}
     * happens to be declared first in {@link #allowlist()} — so the behaviour was pinned by the order
     * of a {@code List.of}, and reordering it would have silently multiplied the configured failure
     * threshold by four. A test that hands in a reversed set can tell the two apart; one that can only
     * call the production set cannot.
     */
    static Class<?> allowlistedFamilyOf(Class<?> evaluatorClass, Iterable<Class<?>> candidates) {
        if (evaluatorClass == null) return null;
        Class<?> root = null;
        for (Class<?> allowed : candidates) {
            if (!allowed.isAssignableFrom(evaluatorClass)) continue;
            // `allowed` is more general than `root` exactly when it can stand in for it.
            if (root == null || allowed.isAssignableFrom(root)) root = allowed;
        }
        return root;
    }

    /** Fail closed before and during compatibility discovery. */
    static void denyAllEligible() {
        replaceDenials(ALLOWED);
    }

    static void replaceDenials(Set<Class<?>> denied) {
        synchronized (deniedBySafety) {
            deniedBySafety.clear();
            deniedBySafety.addAll(denied);
        }
    }

    /** The allowlisted families themselves, for diagnostics that iterate them. */
    public static Set<Class<?>> allowlistedFamilies() {
        return ALLOWED;
    }

    /** Exact-class allowlist membership only. */
    public static boolean isEvaluatorAllowed(Class<?> evaluatorClass) {
        return ALLOWED.contains(evaluatorClass);
    }

    /**
     * True when a denial covers this evaluator, by inheritance rather than identity.
     *
     * <p>Every land evaluator extends {@code WalkNodeEvaluator} and executes its code, so a foreign
     * mixin into Walk modifies the search that a flying, amphibious, frog or creaking mob runs just
     * as much as a zombie's. Matching the denied set exactly would have denied the zombie and kept
     * dispatching the other four through the very code the scan objected to.
     */
    private static boolean isDenied(Class<?> evaluatorClass) {
        if (isDeniedByRuntimeFailure(evaluatorClass)) return true;
        synchronized (deniedBySafety) {
            for (Class<?> denied : deniedBySafety) {
                if (denied.isAssignableFrom(evaluatorClass)) return true;
            }
        }
        return false;
    }

    /**
     * True when an evaluator subclass may run because the operator waived compatibility checking.
     *
     * <p>A mod subclassing an allowlisted evaluator is exactly the "another mod modified pathfinding"
     * case the tier exists to govern. At the tier that ignores the scan entirely, refusing it anyway
     * made the setting mean less than it says.
     */
    private static boolean isWaivableSubclass(Class<?> evaluatorClass) {
        for (Class<?> allowed : ALLOWED) {
            if (allowed.isAssignableFrom(evaluatorClass)) return true;
        }
        return false;
    }

    /**
     * Full gate: admitted by the allowlist or by an explicit waiver, not denied by a foreign mixin,
     * and actually rebuildable.
     *
     * <p>Rebuildability belongs here rather than at dispatch. It was previously discovered one layer
     * down, where a refusal became an abandoned dispatch that had already counted itself, and where
     * the in-game diagnostic reported a mob as eligible that could never run. A gate that answers
     * differently from what happens is not a gate.
     */
    public static boolean isAllowed(Class<?> evaluatorClass) {
        if (!isEvaluatorAllowed(evaluatorClass)
                && !(ActiveCompatibilityPolicy.bypassesScan() && isWaivableSubclass(evaluatorClass))) {
            return false;
        }
        return !isDenied(evaluatorClass) && EvaluatorCloner.canClone(evaluatorClass);
    }

    /**
     * Everything {@link #isAllowed} decides, PLUS the land-registry latch that dispatch also checks.
     *
     * <p>This exists because the startup banner and {@code /pathweaver status} answered a different
     * question from the one dispatch asks, and answered it more optimistically. Dispatch refuses any
     * {@code WalkNodeEvaluator}-derived family — which is five of the six — while Fabric's land
     * path-type registry is unverified, so on a pack where that verification fails the mod would
     * announce "ACTIVE: all 6 movement families can path off-thread" and then run exactly one of them.
     * {@code /pathweaver mobs} consulted the latch and said the opposite, so the mod's own
     * diagnostics contradicted each other.
     *
     * <p>Reporting is not decoration here: it is how an operator decides whether the mod is earning
     * its risk. A single predicate that every reporting site and the dispatch path share is the only
     * way this stays true, and it is the third time a diagnostic has been found disagreeing with what
     * the gates actually do.
     */
    public static boolean canDispatch(Class<?> evaluatorClass) {
        return canDispatch(evaluatorClass, isAllowed(evaluatorClass),
            ActiveCompatibilityPolicy.bypassesScan(),
            FabricLandPathRegistryLatch.allowsWalkDispatch());
    }

    /**
     * The same composition against chosen state.
     *
     * <p>Public, not package-private: the no-argument form is a RECONSTRUCTION of what dispatch does
     * in three separate steps, and a reconstruction nothing pins is how this release's headline bug
     * became re-introducible twice. {@link dev.pathweaver.gate.SafetyGateDispatchParityTest} asserts
     * the reconstruction against the real sequence rather than against itself.
     */
    public static boolean canDispatch(Class<?> evaluatorClass, boolean allowed, boolean bypassesScan,
                                      boolean latchAllows) {
        return allowed && landRegistryPermits(evaluatorClass, bypassesScan, latchAllows);
    }

    /**
     * The land-registry half of the decision, as a pure function so it can actually be tested.
     *
     * <p>Split out because the composite reads three pieces of process-wide state and so could only
     * be exercised by whatever the surrounding test environment happened to be — which is how a
     * predicate that gates both dispatch and every reporting site ended up with no coverage at all.
     *
     * @param bypassesScan the operator waived compatibility checking entirely
     * @param latchAllows Fabric's land path-type registry is verified and still empty
     */
    static boolean landRegistryPermits(Class<?> evaluatorClass, boolean bypassesScan,
                                       boolean latchAllows) {
        return !(isLandDerived(evaluatorClass) && !bypassesScan) || latchAllows;
    }

    /**
     * The predicate the dispatch mixin calls. There is no one-argument convenience wrapper, and that
     * is deliberate.
     *
     * <p>A previous round added a wrapper so the state-injecting form could be tested, then tested
     * the wrapper's callee and not the wrapper. A reviewer replaced the wrapper body with
     * {@code return false;} and the entire 289-test suite stayed green — meaning every land-derived
     * family could be made to dispatch against a populated registry, with the install-time re-check
     * disarmed at the same time, and nothing would notice. Two rounds running, the tests were one hop
     * from the call site; the fix is to remove the hop rather than add another test beside it.
     *
     * @param bypassesScan the operator waived compatibility checking entirely
     */
    public static boolean requiresEmptyLandRegistry(Class<?> evaluatorClass, boolean bypassesScan) {
        return isLandDerived(evaluatorClass) && !bypassesScan;
    }

    /**
     * Resolves block path types through {@code WalkNodeEvaluator}'s code, and so depends on Fabric's
     * land registry staying empty.
     *
     * <p>ONE definition. There were two: this rule was written out separately in the predicate the
     * diagnostics use and in the predicate dispatch uses, and only the first had tests — so reverting
     * the dispatch copy to an exact-class check would have kept every test green while frogs,
     * axolotls, drowned, turtles and creakings dispatched off-thread against a populated registry,
     * with the install-time re-check disarmed too. That is verbatim the bug these tests claim to
     * prevent, invisible because they were asserting the other copy.
     */
    public static boolean isLandDerived(Class<?> evaluatorClass) {
        return net.minecraft.world.level.pathfinder.WalkNodeEvaluator.class
            .isAssignableFrom(evaluatorClass);
    }

    /**
     * True when the land registry is currently keeping every walk-derived family on the main thread.
     *
     * <p>Exists so {@code /pathweaver mobs} stops open-coding the rule. It was the fourth copy, and
     * the whole point of the shared predicate is that a reporting site cannot answer a more
     * optimistic question than dispatch asks.
     */
    public static boolean landRegistryBlocksWalkFamilies() {
        return !ActiveCompatibilityPolicy.bypassesScan()
            && !FabricLandPathRegistryLatch.allowsWalkDispatch();
    }
}
