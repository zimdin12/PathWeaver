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

    private static void status(CommandSourceStack source) {
        PathWeaverConfig config = PathWeaverConfig.get();
        PathWeaverRuntime runtime = PathWeaverRuntime.get();
        ForeignMixinScanner.ScanReport report = ForeignMixinScanner.lastScanReport();

        say(source, "§6PathWeaver status");
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
        say(source, tierLine);
        say(source, "  scan: scanned=" + report.decision().scanned()
            + ", failed=" + report.decision().failed()
            + ", deniedFamilies=" + report.decision().denied().size());

        // Report what the tier DID with the scan, not what the scan found. The unsafe tier waives
        // every denial, and printing the raw decision there told an operator that all six families
        // were synchronous while the server was in fact dispatching all six and installing
        // thousands of paths. A diagnostic that contradicts the running mod is worse than none.
        // waived is what the tier ACTUALLY achieved, not what it asked for. ForeignMixinScanner
        // only clears the denial set when the scan succeeded, so deriving this from the tier alone
        // made the report disagree with the running mod the moment a scan error appeared.
        boolean scanFailed = report.decision().failed() > 0;
        for (String line : scanSummary(report.decision().denied(),
                config.bypassesCompatibilityScan() && !scanFailed, scanFailed,
                undispatchableFamilyNames())) {
            say(source, line);
        }
        java.util.List<String> trusted =
            dev.pathweaver.gate.ForeignMixinScanner.trustedModIdsInUse();
        if (!trusted.isEmpty()) {
            say(source, "  §e" + trusted.size() + " mod(s) running unaudited from trustedMods: §7"
                + String.join(", ", trusted));
        }
        say(source, "  workers: " + runtime.pool().threads()
            + "   maxInFlight: " + runtime.pool().maxInFlight());
        long dispatched = runtime.dispatchedCount();
        say(source, "  since server start: dispatched=" + dispatched
            + ", installed=" + runtime.installedCount()
            + ", discarded=" + runtime.discardedCount());
        for (RequestOutcome outcome : RequestOutcome.values()) {
            long count = runtime.outcomeCount(outcome);
            if (count == 0L) continue;
            // POOL_SATURATED is not drawn from `dispatched` -- RequestOutcome documents it as
            // "not a discard and not a dispatch", and the mixin discards before markDispatched().
            // Dividing it by `dispatched` printed shares above 100% on the one row that means "your
            // configuration is the bottleneck", which is the row most worth reading correctly.
            boolean partOfDispatched = outcome != RequestOutcome.POOL_SATURATED
                && outcome != RequestOutcome.SETUP_FAILED;
            String share = partOfDispatched && dispatched > 0L
                ? String.format(java.util.Locale.ROOT, " (%.1f%%)", 100.0 * count / dispatched) : "";
            // Amber for admission refusal too. It is not a discard -- nothing was computed and
            // thrown away -- but it is not a success either, and printing it green beside `installed`
            // under a footer reading "only the amber rows are wasted work" told an operator their
            // pool being the bottleneck was a good outcome.
            boolean good = !outcome.isDiscard() && outcome != RequestOutcome.POOL_SATURATED;
            say(source, (good ? "    §a" : "    §e") + count + "§r  "
                + outcome.description() + "§7" + share);
        }
        say(source, "  §7Only the amber rows are wasted work. A search that proves no route exists "
            + "succeeded, and a mob that stopped moving is the mob behaving normally.");
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

    /** The families dispatch would still refuse even with nothing denied — see SafetyGate.canDispatch. */
    static List<String> undispatchableFamilyNames() {
        List<String> names = new ArrayList<>();
        for (Class<?> family : dev.pathweaver.gate.SafetyGate.allowlisted()) {
            if (!dev.pathweaver.gate.SafetyGate.canDispatch(family)) names.add(family.getSimpleName());
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
        if (denied.isEmpty()) {
            // "Nothing is denied" is not the same as "anything can run". Dispatch also refuses every
            // WalkNodeEvaluator-derived family -- five of the six -- while Fabric's land path-type
            // registry is unverified, and this line used to report all-clear straight through that
            // while /pathweaver mobs reported the opposite. Ask the same predicate dispatch asks.
            if (undispatchable.isEmpty()) {
                return List.of("  §ano movement family is denied — searches can run off-thread");
            }
            return List.of(
                "  §eno mod is blamed, but " + undispatchable.size() + " of "
                    + dev.pathweaver.gate.SafetyGate.allowlisted().size()
                    + " families still cannot dispatch: §7" + String.join(", ", undispatchable),
                "  §7a mod registered an uncertified land path-type rule, or the Fabric land-registry",
                "  §7hooks could not be verified. Run §f/pathweaver mobs§7 for the per-family reason.");
        }
        // A failed scan outranks the tier. Reporting "running anyway, because the tier is Unsafe"
        // here would invent a risk the operator is not taking AND hide that the mod is inert -- the
        // worst available direction, and the third time this diagnostic has drifted from dispatch.
        if (scanFailed) {
            return List.of(
                "  §cthe compatibility scan FAILED, so every family runs on the server thread",
                "  §7denied: " + String.join(", ", denied),
                "  §7compatibilityTier does not waive this: the tier waives what the scan found, "
                    + "not the scan being unable to look. See the startup log for the errors.");
        }
        if (waived) {
            return List.of(
                "  §e" + denied.size() + " family/families were denied by the scan and are running "
                    + "anyway, because the tier is Unsafe",
                "  §7waived: " + String.join(", ", denied),
                "  §7That is what Unsafe means: uninspected mod code is running on worker threads. "
                    + "Keep backups.");
        }
        return List.of(
            "  §cdenied: " + String.join(", ", denied) + " — those searches run on the server thread",
            "  §7raise the compatibility risk setting, or check the startup log for the mods "
                + "responsible");
    }

    /**
     * Answering this means building one of every registered mob to read the evaluator its navigation
     * really holds, which on a large pack is a couple of hundred constructions inside one tick. That
     * cost is reported rather than estimated: an operator who sees the server hitch deserves to know
     * whether this command caused it, and a number nobody measured is not worth defending.
     */
    private static void mobs(CommandSourceStack source) {
        PathWeaverConfig cfg = PathWeaverConfig.get();
        // Two dispatch gates this used to skip, both of which make every per-type verdict below
        // meaningless when they are shut. Reporting "187 of 187 can path off-thread" while the master
        // switch is off -- including the fail-closed state after a config load error -- is the same
        // "diagnostic disagrees with the code" failure as the scan summary, and this command is the
        // one the README points at to reproduce its published eligibility numbers.
        if (!cfg.enabled) {
            say(source, "§6PathWeaver mobs");
            say(source, "  §cPathWeaver is disabled, so no mob type paths off-thread regardless of "
                + "the per-type rules. Enable it and run this again.");
            return;
        }
        if (!dev.pathweaver.gate.FabricLandPathRegistryLatch.allowsWalkDispatch()
                && !cfg.bypassesCompatibilityScan()) {
            say(source, "§6PathWeaver mobs");
            say(source, "  §ca mod registered an uncertified land path-type rule, so every "
                + "walk-derived family runs on the server thread no matter what the per-type rules "
                + "say. Raising the compatibility risk setting waives this.");
            return;
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
                entity = type.create(source.getLevel(), EntitySpawnReason.COMMAND);
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

        say(source, "§6PathWeaver mob coverage");
        // "eligible", not "can path off-thread". Eligibility is about the evaluator, the PathFinder
        // and the mob's origin; it is not a promise that the mob's actual movement behaviour routes
        // through a dispatch site. Two common ones do not: WallClimberNavigation (spiders) overrides
        // moveTo(Entity, double) without calling super, and MoveToTargetSink -- every brain mob,
        // including villagers, piglins, axolotls, frogs, allays and the warden -- calls createPath
        // directly and then moveTo(Path, double). Those routes are synchronous by construction (see
        // DESIGN.md section 10), so counting them as "can path off-thread" overstates coverage.
        say(source, "  " + eligible + " of " + types + " mob types are eligible");
        say(source, "  §7Eligible means nothing blocks dispatch for this mob. It is not a promise "
            + "that its AI routes through a dispatching call site — brain-driven movement and "
            + "wall-climber chases stay synchronous by design.");
        verdicts.entrySet().stream()
            .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
            .forEach(entry -> say(source,
                (entry.getKey().equals(MobEligibility.ELIGIBLE) ? "  §a" : "  §e")
                    + entry.getValue() + "§r  " + entry.getKey()));
        say(source, "  §7Every vanilla evaluator can run off-thread. What remains synchronous is "
            + "mobs whose evaluator a mod replaced, and — below the unsafe tier — mob classes mods "
            + "added. The compatibility risk setting governs which mods may have touched "
            + "pathfinding, not which searches are safe to move.");
        // Measured at 213 ms on a 222-mod pack: four ticks' budget spent inside one tick. Reporting
        // the number was not enough -- an operator who sees a hitch needs to be told this caused it.
        boolean costly = elapsedMillis >= 50;
        say(source, (costly ? "  §e" : "  §7") + "Built and inspected " + types + " mob types in "
            + elapsedMillis + " ms of one tick"
            + (costly ? ", which is longer than a tick — expect a visible hitch" : "")
            + ". This command is a diagnostic, not something to run on a timer.");
    }

    private static MobEligibility.Verdict verdictFor(Mob mob, boolean moddedAllowed) {
        try {
            PathNavigation navigation = mob.getNavigation();
            NodeEvaluator evaluator = evaluatorOf(navigation);
            return MobEligibility.of(mob.getClass(),
                evaluator == null ? null : evaluator.getClass(),
                pathFinderOf(navigation), moddedAllowed);
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
                    return value == null ? null : value.getClass();
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
