package dev.pathweaver.gate;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Names the mods implicated when a search throws on a worker.
 *
 * <p>This is the half of the failure story that pays on every install. The breaker beside it may
 * never fire — 731 dispatches on the reference pack produced zero search failures — but when a search
 * does throw, the difference between a rate-limited {@code WARN} saying "async path search failed" and
 * a block naming the family, the exception and the mod is the difference between a user who files a
 * usable bug report and a user who uninstalls.
 *
 * <p><b>The honest limit, which the log text states rather than hides.</b> A mixin-injected handler is
 * merged into its <em>target</em> class, so a stack frame normally reads
 * {@code net.minecraft.world.level.pathfinder.WalkNodeEvaluator} and not the mod's own class. Two
 * things are still recoverable: a mod id appearing as a package segment of the
 * frame's class, and Mixin's generated handler method names, which carry the owning mod id in the form
 * {@code handler$xxx000$modid$name}. Neither works for an {@code @Overwrite} or an inlined
 * {@code @Redirect}, where the mod's code becomes indistinguishable from the target's. When nothing
 * can be named the log says so, because printing an empty list would let a reader conclude no mod was
 * involved.
 *
 * <p><b>Never {@code Class.forName}.</b> Resolving a stack frame by loading the class it names can
 * trigger static initialization of arbitrary mod code, on the server thread, inside a tick. Frame
 * class names are matched as strings and nothing here loads anything.
 */
final class ModAttribution {

    /** Loaded mod ids, fetched once and only when something has already gone wrong. */
    private static volatile Set<String> modIds;

    private static final Set<Class<?>> REPORTED_FIRST_FAILURE = ConcurrentHashMap.newKeySet();

    /**
     * What was reported, for tests.
     *
     * <p>Exists because a review deleted {@code reportFirstFailure} and {@code reportTrip} outright
     * and the whole suite stayed green — the "half of this feature that pays on every install" and
     * the block that tells an operator a family has been switched off, both with no coverage of
     * whether they are ever invoked. Asserting on a log appender is not practical; asserting on this
     * is.
     */
    static final java.util.List<String> REPORTS =
        java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    private ModAttribution() {}

    static void reset() {
        REPORTED_FIRST_FAILURE.clear();
        REPORTS.clear();
        modIds = null;
    }

    /** One block per family, on its first failure, whether or not the breaker is armed. */
    static void reportFirstFailure(Class<?> family, Throwable failure) {
        if (!REPORTED_FIRST_FAILURE.add(family)) return;
        WorkerFailureBreaker.logSafely(() -> {
            List<String> suspects = suspects(failure);
            dev.pathweaver.PathWeaver.LOG.warn(
                "==================== PathWeaver ====================");
            dev.pathweaver.PathWeaver.LOG.warn(
                "A {} search threw on a worker thread. That request fell back to vanilla "
                    + "pathfinding; nothing is broken yet.", family.getSimpleName());
            dev.pathweaver.PathWeaver.LOG.warn("  {}: {}",
                failure.getClass().getName(), String.valueOf(failure.getMessage()));
            if (suspects.isEmpty()) {
                dev.pathweaver.PathWeaver.LOG.warn(
                    "  No mod could be named from the stack. That is expected rather than "
                        + "reassuring: a mixin handler is merged into the class it targets, so an "
                        + "@Overwrite or an inlined @Redirect leaves nothing to attribute.");
            } else {
                dev.pathweaver.PathWeaver.LOG.warn("  Mods on the stack: {}",
                    String.join(", ", suspects));
            }
            dev.pathweaver.PathWeaver.LOG.warn(
                "  Logged once per movement family per server session. Repeated failures are "
                    + "counted, and /pathweaver status shows the running total.");
            dev.pathweaver.PathWeaver.LOG.warn(
                "====================================================", failure);
            // Recorded from INSIDE the block, deliberately. Written beside it, a mutation that made
            // logSafely emit nothing left every report test green while the feature produced no
            // user-visible output whatsoever -- the assertion and the log were two independent
            // renderings of the same data, and only the one nobody ships was checked.
            REPORTS.add("first-failure:" + family.getSimpleName());
        });
    }

    /** The trip block: louder, and it corrects the world-start banner rather than leaving it stale. */
    static void reportTrip(Class<?> family, Throwable failure,
                           WorkerFailureBreaker.TripReason reason, int count, int limit,
                           long window) {
        WorkerFailureBreaker.logSafely(() -> {
            dev.pathweaver.PathWeaver.LOG.warn(
                "==================== PathWeaver ====================");
            // Every family the trip actually switches off, not just the one that was counted. Denial
            // is by inheritance, so tripping WalkNodeEvaluator stops Fly, Amphibious, Frog and
            // Creaking too -- an operator told only "WalkNodeEvaluator" goes looking for why their
            // axolotls and bees also stopped being accelerated.
            String affected = String.join(", ", SafetyGate.familiesRefusedByRuntimeFailure());
            dev.pathweaver.PathWeaver.LOG.warn(
                "{} searches have now thrown {} time(s), so PathWeaver has switched these OFF for "
                    + "the rest of this session: {}", family.getSimpleName(), count, affected);
            dev.pathweaver.PathWeaver.LOG.warn(
                "Every mob in that family now paths on the server thread, exactly as it would "
                    + "without this mod installed. Nothing is corrupted by this; it is the safe "
                    + "direction.");
            // The exception, always. A backstop trip happens far from the first failure by
            // construction, so without this the loudest block in the mod names no cause at all.
            dev.pathweaver.PathWeaver.LOG.warn("  Most recent: {}: {}",
                failure.getClass().getName(), String.valueOf(failure.getMessage()));
            List<String> suspects = suspects(failure);
            if (suspects.isEmpty()) {
                dev.pathweaver.PathWeaver.LOG.warn(
                    "  No mod could be named from the stack -- a mixin handler is merged into the "
                        + "class it targets, so an @Overwrite leaves nothing to attribute.");
            } else {
                dev.pathweaver.PathWeaver.LOG.warn("  Mods on the stack: {}",
                    String.join(", ", suspects));
            }
            // Name the rule that ACTUALLY fired. Printing the window threshold after a ceiling trip
            // sent an operator to raise workerFailureLimit, which changes nothing, because the
            // ceiling is not that setting -- an invented cause in the block that exists to give a
            // real one.
            if (reason == WorkerFailureBreaker.TripReason.WINDOW) {
                if (window > 0) {
                    dev.pathweaver.PathWeaver.LOG.warn(
                        "  Rule: {} failures within {} tick(s). Tune workerFailureLimit and "
                            + "workerFailureWindowTicks, or set workerFailureLimit=0 to keep "
                            + "dispatching and only log.", limit, window);
                } else {
                    // workerFailureWindowTicks=0 is the documented "never forget" setting, and the
                    // sentence above renders as "within 0 tick(s)" for anyone who chose it.
                    dev.pathweaver.PathWeaver.LOG.warn(
                        "  Rule: {} failures this session. You set workerFailureWindowTicks=0, so "
                            + "failures never age out. Set workerFailureLimit=0 to keep dispatching "
                            + "and only log.", limit);
                }
            } else {
                // Name the number, and do not claim a setting moves it when it does not. The advice
                // used to say "raising workerFailureLimit raises it too" -- true only above 25, and
                // false at the shipped default of 3, which is the case that reaches this branch.
                dev.pathweaver.PathWeaver.LOG.warn(
                    "  Rule: {} failures in total this session, however far apart. That is a fixed "
                        + "backstop of {}, separate from workerFailureLimit, and it catches a leak "
                        + "too slow to cluster. Raising workerFailureLimit past {} raises the "
                        + "backstop with it; workerFailureLimit=0 disables both.",
                    count, WorkerFailureBreaker.CUMULATIVE_CEILING,
                    WorkerFailureBreaker.CUMULATIVE_CEILING);
            }
            dev.pathweaver.PathWeaver.LOG.warn(
                "  If the startup banner said PathWeaver was ACTIVE, that no longer holds for this "
                    + "family. Restart the server to re-arm it.");
            dev.pathweaver.PathWeaver.LOG.warn(
                "====================================================");
            // Rule AND count, recorded from inside the emitted block. The rule alone left the count
            // argument free -- passing the window's count on a backstop trip survived and would have
            // told an operator that a single failure switched a family off.
            REPORTS.add("trip:" + family.getSimpleName() + ":" + reason + ":" + count);
        });
    }

    /**
     * Mod ids implicated by a throwable's stack, best effort and in stack order.
     *
     * <p>Two independent signals, because either alone misses most real cases: the owning jar of a
     * frame's class, and the mod id Mixin bakes into a generated handler method name.
     */
    static List<String> suspects(Throwable failure) {
        Set<String> found = new LinkedHashSet<>();
        // Bounded. The old guard only caught a throwable that was its own cause; two throwables that
        // cause each other looped forever, on a worker, inside a catch-all that can swallow a
        // throwable but cannot break a loop. Throwable.printStackTrace keeps a seen-set for exactly
        // this reason, and ten frames of causes is already more than any bug report needs.
        int depth = 0;
        for (Throwable t = failure; t != null && depth < 10; t = t.getCause(), depth++) {
            for (StackTraceElement frame : t.getStackTrace()) {
                String fromHandler = modIdFromHandlerName(frame.getMethodName());
                if (fromHandler != null) found.add(fromHandler);
                String fromPackage = modIdForClassName(frame.getClassName());
                if (fromPackage != null) found.add(fromPackage);
            }
            if (t.getCause() == t) break;
        }
        found.remove("minecraft");
        found.remove("java");
        found.remove("pathweaver");
        return List.copyOf(found);
    }

    /**
     * Mixin names a generated handler {@code handler$abc000$modid$originalName}.
     *
     * <p>The mod id is the third {@code $}-separated part. This is a convention rather than an API, so
     * it is read defensively and contributes nothing when the shape does not match.
     */
    static String modIdFromHandlerName(String methodName) {
        if (methodName == null || !methodName.startsWith("handler$")) return null;
        String[] parts = methodName.split("\\$");
        if (parts.length < 4) return null;
        String candidate = parts[2];
        return candidate.isEmpty() ? null : candidate;
    }

    /**
     * Does any loaded mod's id appear as a package segment of this class name?
     *
     * <p>A heuristic, and named as one. The precise alternatives were both rejected:
     * {@code Class.forName} to reach a {@code ProtectionDomain} can trigger static initialization of
     * arbitrary mod code on the server thread inside a tick, and walking every jar in a 300-mod pack
     * to enumerate class names is not something to do while handling a failure. Package segments
     * carry the mod id often enough to be useful — {@code me.jellysquid.mods.lithium.…} names
     * lithium — and when they do not, the handler-name signal usually still fires.
     *
     * <p>Segments shorter than three characters are ignored: a two-letter mod id matching a package
     * segment is coincidence more often than attribution.
     */
    static String modIdForClassName(String className) {
        if (className == null) return null;
        Set<String> ids = modIds;
        if (ids == null) {
            ids = loadModIds();
            modIds = ids;
        }
        for (String segment : className.split("\\.")) {
            if (segment.length() >= 3 && ids.contains(segment)) return segment;
        }
        return null;
    }

    /** Test seam: inject a known id set instead of asking the loader. */
    static void useModIdsForTesting(Set<String> ids) {
        modIds = ids == null ? null : Set.copyOf(ids);
    }

    private static Set<String> loadModIds() {
        try {
            Set<String> ids = new LinkedHashSet<>();
            for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
                ids.add(mod.getMetadata().getId());
            }
            return Set.copyOf(ids);
        } catch (Throwable ignored) {
            // An attribution that cannot be built is a missing line in a log block, not a failure.
            return Set.of();
        }
    }
}
