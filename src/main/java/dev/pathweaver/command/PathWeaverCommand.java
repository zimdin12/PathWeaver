package dev.pathweaver.command;

import com.mojang.brigadier.CommandDispatcher;
import dev.pathweaver.PathWeaverRuntime;
import dev.pathweaver.async.RequestOutcome;
import dev.pathweaver.config.PathWeaverConfig;
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
        say(source, "  enabled: " + config.enabled + "   tier: " + config.compatibilityTier
            + " (frozen at startup)");
        say(source, "  scan: scanned=" + report.decision().scanned()
            + ", failed=" + report.decision().failed()
            + ", deniedFamilies=" + report.decision().denied().size());

        if (report.decision().denied().isEmpty()) {
            say(source, "  §ano movement family is denied — searches can run off-thread");
        } else {
            List<String> denied = new ArrayList<>();
            for (Class<?> family : report.decision().denied()) denied.add(family.getSimpleName());
            say(source, "  §cdenied: " + String.join(", ", denied)
                + " — those searches run on the server thread");
            say(source, "  §7raise the compatibility risk setting, or check the startup log for the "
                + "mods responsible");
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
            String share = dispatched > 0L
                ? String.format(" (%.1f%%)", 100.0 * count / dispatched) : "";
            say(source, (outcome.isDiscard() ? "    §e" : "    §a") + count + "§r  "
                + outcome.description() + "§7" + share);
        }
        say(source, "  §7Only the amber rows are wasted work. A search that proves no route exists "
            + "succeeded, and a mob that stopped moving is the mob behaving normally.");
    }

    /**
     * Answering this means building one of every registered mob to read the evaluator its navigation
     * really holds, which on a large pack is a couple of hundred constructions inside one tick. That
     * cost is reported rather than estimated: an operator who sees the server hitch deserves to know
     * whether this command caused it, and a number nobody measured is not worth defending.
     */
    private static void mobs(CommandSourceStack source) {
        boolean moddedAllowed = PathWeaverConfig.get().moddedMobAsyncAllowed();
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
        say(source, "  " + eligible + " of " + types + " mob types can path off-thread");
        verdicts.entrySet().stream()
            .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
            .forEach(entry -> say(source,
                (entry.getKey().equals(MobEligibility.ELIGIBLE) ? "  §a" : "  §e")
                    + entry.getValue() + "§r  " + entry.getKey()));
        say(source, "  §7Every vanilla evaluator can run off-thread. What remains synchronous is "
            + "mobs whose evaluator a mod replaced, and — below the unsafe tier — mob classes mods "
            + "added. The compatibility risk setting governs which mods may have touched "
            + "pathfinding, not which searches are safe to move.");
        say(source, "  §7Built and inspected " + types + " mob types in " + elapsedMillis
            + " ms of one tick. This command is a diagnostic, not something to run on a timer.");
    }

    private static MobEligibility.Verdict verdictFor(Mob mob, boolean moddedAllowed) {
        try {
            NodeEvaluator evaluator = evaluatorOf(mob.getNavigation());
            return MobEligibility.of(mob.getClass(),
                evaluator == null ? null : evaluator.getClass(), moddedAllowed);
        } catch (Throwable failed) {
            return new MobEligibility.Verdict(false,
                "could not be inspected (" + failed.getClass().getSimpleName() + ")");
        }
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
