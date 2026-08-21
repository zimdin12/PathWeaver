package dev.pathweaver.command;

import com.mojang.brigadier.CommandDispatcher;
import dev.pathweaver.PathWeaverRuntime;
import dev.pathweaver.async.RequestOutcome;
import dev.pathweaver.config.CompatibilityTier;
import dev.pathweaver.config.PathWeaverConfig;
import dev.pathweaver.gate.ActiveCompatibilityPolicy;
import dev.pathweaver.gate.ForeignMixinScanner;
import dev.pathweaver.gate.MobOriginGate;
import dev.pathweaver.gate.SafetyGate;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.pathfinder.NodeEvaluator;

/**
 * In-game answers to "is this doing anything, and why not".
 *
 * <p>Until now the only observable output was three aggregate counters, which cannot answer the
 * first question anyone asks: does this mod cover <em>my</em> mobs? Diagnosing that required
 * building a throwaway probe against the mod's internals. The rules are not complicated, they were
 * simply not visible, and a mod that switches itself off for good reasons still owes the operator
 * the reason.
 *
 * <p>Eligibility is answered by calling the same gates the dispatch path calls, never by
 * re-implementing their rules — a diagnostic that disagrees with the code is worse than none.
 */
public final class PathWeaverCommand {
    private PathWeaverCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("pathweaver")
            .requires(source -> Commands.LEVEL_GAMEMASTERS.check(source.permissions()))
            .then(Commands.literal("status").executes(context -> {
                status(context.getSource());
                return 1;
            }))
            .then(Commands.literal("mobs").executes(context -> {
                mobs(context.getSource());
                return 1;
            })));
    }

    /**
     * The lines {@code /pathweaver status} prints, separated from the printing. See
     * {@link #mobsLines} for why.
     */
    static java.util.List<String> statusLines() {
        java.util.List<String> out = new java.util.ArrayList<>();
        PathWeaverConfig config = PathWeaverConfig.get();
        PathWeaverRuntime runtime = PathWeaverRuntime.get();
        ForeignMixinScanner.ScanReport report = ForeignMixinScanner.lastScanReport();

        out.add("§6PathWeaver status");
        // Report the tier that is actually in force, not the one on disk. The field is what a
        // settings save writes; the policy was frozen at scan time and does not follow it. Printing
        // the field labelled "frozen at startup" told an operator who had just switched to AUDITED
        // mid-session that they were running checked, on a session still running unchecked -- which
        // is now the common direction of travel, since the shipped default is UNSAFE.
        CompatibilityTier inForce = ActiveCompatibilityPolicy.bypassesScan()
            ? CompatibilityTier.UNSAFE
            : CompatibilityTier.AUDITED;
        String tierLine = "  enabled: " + config.enabled + "   tier in force: " + inForce;
        if (config.compatibilityTier != inForce) {
            tierLine += " (config says " + config.compatibilityTier + " -- restart to apply)";
        }
        out.add(tierLine);
        out.add(ScanCounts.of(report.decision()).line());
        // Printed BEFORE the scan narrative below, and separately from it. scanSummary is handed
        // report.decision().denied() -- the scan's findings -- so a runtime trip is either invisible
        // there (where the scan already covers that family) or explained as "most likely an evaluator
        // that cannot be cloned on this JVM" (where it does not). Both are wrong, in opposite
        // directions, and both are causes the code invented rather than knew.
        // Failures that have NOT yet tripped anything, because the log block promises the running
        // total is here and it was not: status stayed silent until a trip, so an operator following
        // that instruction found nothing and had no way to tell one bad tick from a building problem.
        for (Class<?> family : dev.pathweaver.gate.SafetyGate.allowlisted()) {
            int recent = dev.pathweaver.gate.WorkerFailureBreaker.windowedCount(family);
            int total = dev.pathweaver.gate.WorkerFailureBreaker.cumulativeCount(family);
            if (total <= 0 || dev.pathweaver.gate.SafetyGate.isDeniedByRuntimeFailure(family)) {
                continue;
            }
            // BOTH numbers, because they answer different questions and only one of them is the one
            // the trip log promises. The window is what trips a family; the session total is what
            // tells an operator whether this is one bad tick or a problem building all afternoon.
            // Printing only the window reported "1 failure" for a family sitting four short of the
            // session backstop.
            out.add("  §e" + family.getSimpleName() + ": " + total + " search failure(s) this session"
                + (recent == total ? "" : " (" + recent + " in the current window)")
                + ", still dispatching. See the PathWeaver block in the log.");
        }
        java.util.Set<Class<?>> tripped = dev.pathweaver.gate.SafetyGate.runtimeFailureDenials();
        if (!tripped.isEmpty()) {
            // Name the CLOSURE, not the set. The set holds classes; the gate refuses by inheritance,
            // so a single WalkNodeEvaluator entry actually switches off Walk, Fly, Amphibious, Frog
            // and Creaking -- five of six. Reporting the set said "1 family/families" while five were
            // off and /pathweaver mobs listed at least three, in the release whose thesis is that the
            // diagnostics agree. PathWeaverRuntime.reportWhetherItIsDoingAnything already carries a
            // twelve-line comment about this exact trap; this code did not read it.
            List<String> names = new java.util.ArrayList<>();
            for (Class<?> family : dev.pathweaver.gate.SafetyGate.allowlisted()) {
                if (dev.pathweaver.gate.SafetyGate.isDeniedByRuntimeFailure(family)) {
                    names.add(family.getSimpleName());
                }
            }
            java.util.Collections.sort(names);
            out.add("  §c" + names.size() + " family/families switched OFF after their searches "
                + "threw on a worker: " + String.join(", ", names));
            out.add("  §7Those mobs path on the server thread exactly as they would without this "
                + "mod. No compatibility setting affects this; the log block names what threw and, "
                + "where it can, which mod. Restart to re-arm.");
        }

        // Report what the tier DID with the scan, not what the scan found. The unsafe tier waives
        // every denial, and printing the raw decision there told an operator that all six families
        // were synchronous while the server was in fact dispatching all six and installing
        // thousands of paths. A diagnostic that contradicts the running mod is worse than none.
        // waived is what the tier ACTUALLY achieved, not what it asked for. ForeignMixinScanner
        // only clears the denial set when the scan succeeded, so deriving this from the tier alone
        // made the report disagree with the running mod the moment a scan error appeared.
        boolean scanFailed = ForeignMixinScanner.scanFailed();
        for (String line : scanSummary(report.decision().denied(),
                config.bypassesCompatibilityScan() && !scanFailed, scanFailed,
                undispatchableFamilyNames(report.decision().denied()))) {
            out.add(line);
        }
        java.util.List<String> trusted =
            dev.pathweaver.gate.ForeignMixinScanner.trustedModIdsInUse();
        if (!trusted.isEmpty()) {
            out.add("  §e" + trusted.size() + " mod(s) running unaudited from trustedMods: §7"
                + String.join(", ", trusted));
        }
        out.add("  workers: " + runtime.pool().threads()
            + "   maxInFlight: " + runtime.pool().maxInFlight());
        long dispatched = runtime.dispatchedCount();
        out.add("  since server start: dispatched=" + dispatched
            + ", installed=" + runtime.installedCount()
            + ", discarded=" + runtime.discardedCount());
        for (RequestOutcome outcome : RequestOutcome.values()) {
            long count = runtime.outcomeCount(outcome);
            if (count == 0L) continue;
            // Both rules now live on the outcome. They were two hard-coded lists here, and the
            // second constant added to them missed both: a pre-admission setup failure printed green
            // beside `installed` with a 136760% share.
            String share = outcome.countsAgainstDispatched() && dispatched > 0L
                ? String.format(java.util.Locale.ROOT, " (%.1f%%)", 100.0 * count / dispatched) : "";
            out.add((outcome.isGoodNews() ? "    §a" : "    §e") + count + "§r  "
                + outcome.description() + "§7" + share);
        }
        out.add("  §7Green means the search produced an answer. Amber means it did not -- most "
            + "of that is normal (a mob stopping, a request superseded), and only the failure rows "
            + "are work actually wasted. Rows with no percentage never reached a worker.");
        return java.util.List.copyOf(out);
    }

    private static void status(CommandSourceStack source) {
        for (String line : statusLines()) say(source, line);
    }


    /**
     * What the tier DID with the scan, which is not always what the scan found.
     *
     * <p>Extracted so it can be tested without a server. Printing the raw scan decision told an
     * operator on the unsafe tier that all six movement families were synchronous, while that same
     * server was dispatching all six and installing thousands of paths. A diagnostic that
     * contradicts the running mod is worse than no diagnostic, and this is the second time that has
     * happened here, so the rule is now pinned by a test rather than by care.
     */
    static List<String> scanSummary(java.util.Collection<Class<?>> deniedFamilies, boolean waived) {
        return scanSummary(deniedFamilies, waived, false);
    }

    static List<String> scanSummary(java.util.Collection<Class<?>> deniedFamilies, boolean waived,
                                    boolean scanFailed) {
        return scanSummary(deniedFamilies, waived, scanFailed, List.of());
    }

    /**
     * Families refused BY a denial through inheritance, so the report can name them.
     *
     * <p>{@code isDenied} matches with {@code isAssignableFrom}, so denying {@code WalkNodeEvaluator}
     * alone refuses five families while the denied list names one. Round three reported the other
     * four as "a different reason" and invented a cause for them; round four subtracted them and made
     * them vanish, so status implied one family refused while the banner said five. Both were wrong in
     * opposite directions. They are neither invented nor hidden now: they are named, attributed to the
     * denial that actually causes them.
     */
    static List<String> familiesRefusedByInheritance(java.util.Collection<Class<?>> deniedFamilies) {
        List<String> names = new ArrayList<>();
        for (Class<?> family : dev.pathweaver.gate.SafetyGate.allowlisted()) {
            if (deniedFamilies.contains(family)) continue;
            for (Class<?> denied : deniedFamilies) {
                if (denied.isAssignableFrom(family)) { names.add(family.getSimpleName()); break; }
            }
        }
        return names;
    }

    static List<String> undispatchableFamilyNames(java.util.Collection<Class<?>> deniedFamilies) {
        List<String> names = new ArrayList<>();
        for (Class<?> family : dev.pathweaver.gate.SafetyGate.allowlisted()) {
            if (dev.pathweaver.gate.SafetyGate.canDispatch(family)) continue;
            // Subtract by DENIAL CLOSURE, not by name. `isDenied` matches with isAssignableFrom, so
            // denying WalkNodeEvaluator alone refuses all five land families while the denied list
            // names exactly one. Subtracting by name left the other four looking like a separate
            // problem, and the report then invented a cause for them -- the third round running that
            // this diagnostic named a cause that was not the cause.
            boolean coveredByADenial = false;
            for (Class<?> denied : deniedFamilies) {
                if (denied.isAssignableFrom(family)) { coveredByADenial = true; break; }
            }
            if (!coveredByADenial) names.add(family.getSimpleName());
        }
        return names;
    }

    /**
     * @param scanFailed the scan errored, so no tier can waive its denials
     */
    static List<String> scanSummary(java.util.Collection<Class<?>> deniedFamilies, boolean waived,
                                    boolean scanFailed, List<String> undispatchable) {
        List<String> denied = new ArrayList<>();
        for (Class<?> family : deniedFamilies) denied.add(family.getSimpleName());
        // Families dispatch refuses that the scan did NOT deny. Computed once and appended to every
        // return path: it used to be computed inside the denied.isEmpty() branch and discarded
        // everywhere else, so a partial denial plus a closed latch had the banner reporting 6 of 6
        // refused while this line reported 1 -- the count disagreement this release exists to end,
        // surviving in the site the README points at.
        // Already closure-subtracted by the producer; only strip exact-name repeats.
        List<String> extra = new ArrayList<>();
        for (String name : undispatchable) {
            if (!denied.contains(name)) extra.add(name);
        }
        // A failed scan outranks the tier. Reporting "running anyway, because the tier is Unsafe"
        // here would invent a risk the operator is not taking AND hide that the mod is inert -- the
        // worst available direction, and the third time this diagnostic has drifted from dispatch.
        if (scanFailed) {
            return withExtra(List.of(
                "  §cthe compatibility scan FAILED, so every family runs on the server thread",
                "  §7denied: " + String.join(", ", denied),
                "  §7compatibilityTier does not waive this: the tier waives what the scan found, "
                    + "not the scan being unable to look. See the startup log for the errors."), extra);
        }
        if (denied.isEmpty()) {
            // "Nothing is denied" is not the same as "anything can run". Dispatch also refuses every
            // WalkNodeEvaluator-derived family -- five of the six -- while Fabric's land path-type
            // registry is unverified, and this line used to report all-clear straight through that
            // while /pathweaver mobs reported the opposite. Ask the same predicate dispatch asks.
            if (undispatchable.isEmpty()) {
                return List.of("  §ano movement family is denied — searches can run off-thread");
            }
            // Only blame the land registry when the land registry is what refused. `undispatchable`
            // is `!canDispatch`, which is also false when an evaluator cannot be cloned or the scan
            // has not published yet -- so asserting a land-registry cause here mirrored the banner's
            // mistake in the opposite direction: one blamed cloning for a latch refusal, the other
            // blamed the latch for a clone failure.
            List<String> lines = new ArrayList<>();
            lines.add("  §eno mod is blamed, but " + undispatchable.size() + " of "
                + dev.pathweaver.gate.SafetyGate.allowlisted().size()
                + " families still cannot dispatch: §7" + String.join(", ", undispatchable));
            if (dev.pathweaver.gate.SafetyGate.landRegistryBlocksWalkFamilies()) {
                lines.add("  §7a mod registered an uncertified land path-type rule, or the Fabric "
                    + "land-registry");
                lines.add("  §7hooks could not be verified. compatibilityTier=UNSAFE waives this; "
                    + "trustedMods does not.");
            } else {
                lines.add("  §7not the land registry -- most likely an evaluator that cannot be "
                    + "cloned on this JVM.");
                lines.add("  §7Changing compatibilityTier or trustedMods will not help.");
            }
            lines.add("  §7Run §f/pathweaver mobs§7 for the per-family reason.");
            return List.copyOf(lines);
        }
        if (waived) {
            // The tier waives SCAN denials. It does not waive a runtime trip, and cannot: the
            // breaker's denials live in a separate set that no tier consults. Listing a
            // breaker-stopped family as "running anyway" contradicted the switched-OFF line printed
            // a few rows above in the same output, and the optimistic line is the one an operator
            // pastes into a bug report.
            List<String> stillRunning = new java.util.ArrayList<>();
            List<String> stoppedByBreaker = new java.util.ArrayList<>();
            for (Class<?> family : deniedFamilies) {
                (dev.pathweaver.gate.SafetyGate.isDeniedByRuntimeFailure(family)
                    ? stoppedByBreaker : stillRunning).add(family.getSimpleName());
            }
            List<String> lines = new java.util.ArrayList<>();
            lines.add("  §e" + stillRunning.size() + " family/families were denied by the scan and "
                + "are running anyway, because the tier is Unsafe");
            if (!stillRunning.isEmpty()) {
                lines.add("  §7waived: " + String.join(", ", stillRunning));
            }
            if (!stoppedByBreaker.isEmpty()) {
                lines.add("  §c" + stoppedByBreaker.size() + " of those are NOT running: "
                    + String.join(", ", stoppedByBreaker) + " — switched off after their searches "
                    + "threw. No tier waives that.");
            }
            lines.add("  §7That is what Unsafe means: uninspected mod code is running on worker "
                + "threads. Keep backups.");
            return withExtra(List.copyOf(lines), extra);
        }
        List<String> inherited = familiesRefusedByInheritance(deniedFamilies);
        int refused = denied.size() + inherited.size();
        return withExtra(List.of(
            "  §cdenied: " + String.join(", ", denied)
                + (inherited.isEmpty() ? "" : " (and " + String.join(", ", inherited)
                    + " by inheritance)")
                + " — " + refused + " of " + dev.pathweaver.gate.SafetyGate.allowlisted().size()
                + " families run on the server thread",
            "  §7raise the compatibility risk setting, or check the startup log for the mods "
                + "responsible"), extra);
    }

    /**
     * Append the families dispatch refuses that the scan did not deny.
     *
     * <p>Without this the report answers a narrower question than the banner: a mod denying only Swim
     * while the land registry holds back the other five produced "denied: SwimNodeEvaluator" here and
     * "doing NOTHING on this pack. All 6" in the log, from the same process, in the same second.
     */
    private static List<String> withExtra(List<String> base, List<String> extra) {
        if (extra.isEmpty()) return base;
        List<String> lines = new ArrayList<>(base);
        lines.add("  §e" + extra.size() + " more cannot dispatch for a different reason: §7"
            + String.join(", ", extra));
        lines.add(dev.pathweaver.gate.SafetyGate.landRegistryBlocksWalkFamilies()
            ? "  §7Fabric's land path-type registry is holding those back; "
                + "compatibilityTier=UNSAFE waives it, trustedMods does not."
            : "  §7most likely an evaluator that cannot be cloned on this JVM.");
        return List.copyOf(lines);
    }

    /**
     * Answering this means building one of every registered mob to read the evaluator its navigation
     * really holds, which on a large pack is a couple of hundred constructions inside one tick. That
     * cost is reported rather than estimated: an operator who sees the server hitch deserves to know
     * whether this command caused it, and a number nobody measured is not worth defending.
     */
    /**
     * The lines {@code /pathweaver mobs} prints, separated from the printing.
     *
     * <p>Extracted so they can be asserted. As a {@code private static void (CommandSourceStack)}
     * this body had no seam at all, and a review compiled several mutations inside it that no test
     * could see -- including reading the mod-added-mob flag from the config and then using
     * {@code true} anyway, which would reprint the very number this release had to correct. None of
     * them changes dispatch; all of them make the mod misreport itself, which is the defect class
     * the last several review rounds have been about.
     */
    static java.util.List<String> mobsLines(net.minecraft.server.level.ServerLevel level) {
        java.util.List<String> out = new java.util.ArrayList<>();
        PathWeaverConfig cfg = PathWeaverConfig.get();
        // Two dispatch gates this used to skip, both of which make every per-type verdict below
        // meaningless when they are shut. Reporting "187 of 187 can path off-thread" while the master
        // switch is off -- including the fail-closed state after a config load error -- is the same
        // "diagnostic disagrees with the code" failure as the scan summary, and this command is the
        // one the README points at to reproduce its published eligibility numbers.
        if (!cfg.enabled) {
            out.add("§6PathWeaver mobs");
            out.add("  §cPathWeaver is disabled, so no mob type paths off-thread regardless of "
                + "the per-type rules. Enable it and run this again.");
            return java.util.List.copyOf(out);
        }
        boolean mobsScanFailed =
            dev.pathweaver.gate.ForeignMixinScanner.scanFailed();
        if (mobsScanFailed) {
            out.add("§6PathWeaver mobs");
            out.add("  §cthe compatibility scan could not complete, so every family is denied "
                + "and no per-type rule applies. compatibilityTier does NOT waive this -- the tier "
                + "waives what the scan found, not the scan being unable to look.");
            return java.util.List.copyOf(out);
        }
        // Shared with dispatch, the banner and status. This was a fourth open-coded copy of the
        // same rule, which is exactly the drift the shared predicate exists to prevent.
        if (dev.pathweaver.gate.SafetyGate.landRegistryBlocksWalkFamilies()) {
            out.add("§6PathWeaver mobs");
            out.add("  §ceither a mod registered an uncertified land path-type rule, or the "
                + "Fabric land-registry hooks could not be verified against this Fabric API build. "
                + "Every walk-derived family runs on the server thread no matter what the per-type "
                + "rules say. compatibilityTier=UNSAFE waives this; trustedMods does not.");
            // Do NOT return. Swim-family mobs -- squid, cod, salmon, tropical fish -- really are
            // dispatching in this state, and answering "nothing works" when one family does is the
            // same under-reporting the rest of this release exists to remove. The per-type scan below
            // is correct here; the line above explains why the land families are missing from it.
            out.add("  §7Swim-family mobs are unaffected and still dispatch; per-type verdicts "
                + "follow.");
        }
        boolean moddedAllowed = cfg.moddedMobAsyncAllowed();
        Map<String, Integer> verdicts = new LinkedHashMap<>();
        int types = 0;
        int eligible = 0;
        long startedAt = System.nanoTime();

        for (Identifier id : BuiltInRegistries.ENTITY_TYPE.keySet()) {
            EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getValue(id);
            if (type == null) continue;
            Entity entity;
            try {
                // Not added to the level, so it needs no cleanup; discarding one would touch state
                // it never acquired.
                entity = type.create(level, EntitySpawnReason.COMMAND);
            } catch (Throwable notConstructible) {
                continue;
            }
            if (!(entity instanceof Mob mob)) continue;
            types++;
            MobEligibility.Verdict verdict = verdictFor(mob, moddedAllowed);
            if (verdict.eligible()) eligible++;
            verdicts.merge(verdict.reason(), 1, Integer::sum);
        }
        long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000L;

        out.add("§6PathWeaver mob coverage");
        // "eligible", not "can path off-thread". Eligibility is about the evaluator, the PathFinder
        // and the mob's origin; it is not a promise that the mob's actual movement behaviour routes
        // through a dispatch site. Two common ones do not: WallClimberNavigation (spiders) overrides
        // moveTo(Entity, double) without calling super, and MoveToTargetSink -- every brain mob,
        // including villagers, piglins, axolotls, frogs, allays and the warden -- calls createPath
        // directly and then moveTo(Path, double). Those routes are synchronous by construction (see
        // DESIGN.md section 10), so counting them as "can path off-thread" overstates coverage.
        // Wall-climbers were in that sentence until 0.6.0 and are not any more: WallClimberNavigation
        // overrides moveTo(Entity, double), and this release injects into the override. Leaving them
        // named here would have shipped the release's own headline as a caveat against itself.
        out.add("  " + eligible + " of " + types + " mob types are eligible");
        out.add("  §7Eligible means nothing blocks dispatch for this mob. It is not a promise "
            + "that its AI routes through a dispatching call site — brain-driven movement "
            + "(villagers, piglins, axolotls, allays, the warden) stays synchronous by design. "
            + "Wall-climber chases did too until 0.6.0, and now dispatch.");
        verdicts.entrySet().stream()
            .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
            .forEach(entry -> out.add(
                (entry.getKey().equals(MobEligibility.ELIGIBLE) ? "  §a" : "  §e")
                    + entry.getValue() + "§r  " + entry.getKey()));
        out.add("  §7Every vanilla evaluator can run off-thread. What remains synchronous is "
            + "mobs whose evaluator a mod replaced, and — below the unsafe tier — mob classes mods "
            + "added. The compatibility risk setting governs which mods may have touched "
            + "pathfinding, not which searches are safe to move.");
        // Measured at 213 ms on a 222-mod pack: four ticks' budget spent inside one tick. Reporting
        // the number was not enough -- an operator who sees a hitch needs to be told this caused it.
        boolean costly = elapsedMillis >= 50;
        out.add((costly ? "  §e" : "  §7") + "Built and inspected " + types + " mob types in "
            + elapsedMillis + " ms of one tick"
            + (costly ? ", which is longer than a tick — expect a visible hitch" : "")
            + ". This command is a diagnostic, not something to run on a timer.");
        return java.util.List.copyOf(out);
    }

    private static void mobs(CommandSourceStack source) {
        for (String line : mobsLines(source.getLevel())) say(source, line);
    }

    /**
     * The two scan numbers, each read from its own source.
     *
     * <p>Two names, because these are two different numbers and both were once called
     * "deniedFamilies". Measured on the real pack at the shipped default, same server and same
     * second: the startup log said {@code deniedFamilies=0} and this line said
     * {@code deniedFamilies=6}. Both were correct — the log reports what is ENFORCED, this reports
     * what the scan FOUND before the tier waived it — and an operator reading one label with two
     * meanings has no way to know that. README teaches the log line, so the log line keeps the name.
     *
     * <p>A record that reads both sources itself, rather than four arguments at a call site, because
     * a reviewer swapped the two sources and every assertion still passed: both labels were still
     * printed and both sources still read, so a bytecode contract structurally could not tell. The
     * only thing that can is a value, and a value needs somewhere to be computed that a test can
     * reach.
     */
    record ScanCounts(int scanned, int failed, int deniedByScan, int enforced) {
        static ScanCounts of(ForeignMixinScanner.ScanDecision decision) {
            return new ScanCounts(decision.scanned(), decision.failed(), decision.denied().size(),
                dev.pathweaver.gate.SafetyGate.scanEnforcedFamilyCount());
        }

        String line() {
            return "  scan: scanned=" + scanned + ", failed=" + failed
                + ", deniedByScan=" + deniedByScan + ", enforced=" + enforced;
        }
    }

    private static MobEligibility.Verdict verdictFor(Mob mob, boolean moddedAllowed) {
        return verdictFor(mob, moddedAllowed, MobEligibility.LandRegistry.live());
    }

    private static MobEligibility.Verdict verdictFor(Mob mob, boolean moddedAllowed,
                                                     MobEligibility.LandRegistry landRegistry) {
        try {
            PathNavigation navigation = mob.getNavigation();
            NodeEvaluator evaluator = evaluatorOf(navigation);
            return MobEligibility.of(mob.getClass(),
                evaluator == null ? null : evaluator.getClass(),
                pathFinderOf(navigation), moddedAllowed, landRegistry);
        } catch (Throwable failed) {
            return new MobEligibility.Verdict(false,
                "could not be inspected (" + failed.getClass().getSimpleName() + ")");
        }
    }

    /**
     * The PathFinder the navigation really holds, read from the field dispatch checks.
     *
     * <p>Returns null when it cannot be read, which {@link MobEligibility} treats as "not inspected"
     * rather than as a refusal — a diagnostic that cannot see a field should not invent a verdict.
     */
    /**
     * Stands for "the field exists and holds null", which refuses, unlike "unreadable", which does
     * not invent a verdict. Any class distinct from PathFinder and its subclasses works; this one is
     * private so it can never be a real evaluator.
     */
    private static final class NoPathFinder { private NoPathFinder() { } }
    static final Class<?> NULL_PATH_FINDER = NoPathFinder.class;

    private static Class<?> pathFinderOf(PathNavigation navigation) {
        for (Class<?> type = navigation.getClass(); type != null; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (!net.minecraft.world.level.pathfinder.PathFinder.class
                        .isAssignableFrom(field.getType())) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    Object value = field.get(navigation);
                    // NULL_PATH_FINDER, not null. Returning null for "the field holds null" made it
                    // indistinguishable from "no such field" and "could not read it", and only the
                    // first of those refuses. Dispatch declines on `pathFinder == null` as well as on
                    // a subclass, so a navigation that builds its PathFinder lazily reported eligible
                    // here while returning to vanilla on every tick.
                    return value == null ? NULL_PATH_FINDER : value.getClass();
                } catch (ReflectiveOperationException | RuntimeException unreadable) {
                    return null;
                }
            }
        }
        return null;
    }

    /** Read the evaluator the search would really use, rather than guessing from the mob type. */
    private static NodeEvaluator evaluatorOf(PathNavigation navigation) throws IllegalAccessException {
        for (Class<?> type = navigation.getClass(); type != null; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (NodeEvaluator.class.isAssignableFrom(field.getType())) {
                    field.setAccessible(true);
                    return (NodeEvaluator) field.get(navigation);
                }
            }
        }
        return null;
    }

    private static void say(CommandSourceStack source, String message) {
        source.sendSuccess(() -> Component.literal(message), false);
    }
}
