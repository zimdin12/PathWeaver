package dev.pathweaver.command;

import com.mojang.brigadier.CommandDispatcher;
import dev.pathweaver.PathWeaverRuntime;
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
        say(source, "  since server start: dispatched=" + runtime.dispatchedCount()
            + ", installed=" + runtime.installedCount()
            + ", discarded=" + runtime.discardedCount());
        say(source, "  §7discarded counts results that stopped being wanted, including every mob "
            + "that simply stopped moving. It is not a failure count.");
    }

    private static void mobs(CommandSourceStack source) {
        boolean moddedAllowed = PathWeaverConfig.get().moddedMobAsyncAllowed();
        Map<String, Integer> verdicts = new LinkedHashMap<>();
        int types = 0;
        int eligible = 0;

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
            verdicts.merge(verdictFor(mob, moddedAllowed), 1, Integer::sum);
            if (verdicts.containsKey(ELIGIBLE)) eligible = verdicts.get(ELIGIBLE);
        }

        say(source, "§6PathWeaver mob coverage");
        say(source, "  " + eligible + " of " + types + " mob types can path off-thread");
        verdicts.entrySet().stream()
            .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
            .forEach(entry -> say(source, (entry.getKey().equals(ELIGIBLE) ? "  §a" : "  §e")
                + entry.getValue() + "§r  " + entry.getKey()));
        say(source, "  §7Every vanilla evaluator can run off-thread. What remains synchronous is "
            + "mobs whose evaluator a mod replaced, and — below the unsafe tier — mob classes mods "
            + "added. The compatibility risk setting governs which mods may have touched "
            + "pathfinding, not which searches are safe to move.");
    }

    private static final String ELIGIBLE = "eligible";

    private static String verdictFor(Mob mob, boolean moddedAllowed) {
        try {
            PathNavigation navigation = mob.getNavigation();
            NodeEvaluator evaluator = evaluatorOf(navigation);
            boolean evaluatorOk = evaluator != null && SafetyGate.isAllowed(evaluator.getClass());
            boolean originOk = MobOriginGate.isAllowed(mob.getClass(), moddedAllowed);
            if (evaluatorOk && originOk) return ELIGIBLE;
            if (!originOk && !evaluatorOk) {
                return "mob class added by a mod, and its evaluator is not a vanilla one";
            }
            if (!originOk) {
                return "mob class added by a mod (enable \"Also speed up mobs added by mods\")";
            }
            if (evaluator == null) return "navigation uses no node evaluator";
            if (!dev.pathweaver.async.EvaluatorCloner.canClone(evaluator.getClass())) {
                return "uses " + evaluator.getClass().getSimpleName()
                    + ", which has no constructor shape we can rebuild";
            }
            return "uses " + evaluator.getClass().getSimpleName() + ", which is not a vanilla evaluator";
        } catch (Throwable failed) {
            return "could not be inspected (" + failed.getClass().getSimpleName() + ")";
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
