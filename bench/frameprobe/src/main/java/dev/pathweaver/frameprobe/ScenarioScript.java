package dev.pathweaver.frameprobe;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs a benchmark scenario from inside the game, so nothing has to type into a window.
 *
 * <p>The first client harness drove chat with injected keystrokes. Those go to whichever window is in
 * front, and when the game lost focus a campaign typed its commands into the operator's terminal. A
 * command executed by the integrated server needs no window at all.
 *
 * <p>Reads {@code pwprobe-script.txt} from the game directory, one step per line:
 * {@code <seconds after the first player joins>|<command without the slash>}. Commands run as the
 * server, positioned at the first player ({@code execute as <player> at @s run ...}), in order, each
 * once. No file, no scenario: the probe then only measures.
 */
final class ScenarioScript {
    private record Step(long atTick, String command) {}

    private final List<Step> steps = new ArrayList<>();
    private int next;
    private long joinedTick = -1;

    ScenarioScript() {
        Path file = FabricLoader.getInstance().getGameDir().resolve("pwprobe-script.txt");
        if (!Files.isRegularFile(file)) return;
        try {
            for (String line : Files.readAllLines(file)) {
                String trimmed = line.strip();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                int bar = trimmed.indexOf('|');
                steps.add(new Step(Math.round(Double.parseDouble(trimmed.substring(0, bar)) * 20.0),
                    trimmed.substring(bar + 1).strip()));
            }
            FrameProbe.LOG.info("[PWPROBE] scenario loaded: {} steps from {}", steps.size(), file);
        } catch (IOException | RuntimeException e) {
            FrameProbe.LOG.error("[PWPROBE] scenario file unreadable, running none of it", e);
            steps.clear();
        }
    }

    /** Called every server tick. Ticks, not wall time: the scenario advances with the game. */
    void tick(MinecraftServer server) {
        if (next >= steps.size()) return;
        var players = server.getPlayerList().getPlayers();
        if (players.isEmpty()) return;
        long now = server.getTickCount();
        if (joinedTick < 0) joinedTick = now;
        String name = players.getFirst().getGameProfile().name();
        while (next < steps.size() && now - joinedTick >= steps.get(next).atTick()) {
            String command = steps.get(next++).command();
            FrameProbe.LOG.info("[PWPROBE] step: {}", command);
            // "server " runs the rest as the server itself, for commands such as spark's that are not
            // about a position; everything else runs as the player, where the player stands.
            String full = command.startsWith("server ") ? command.substring(7)
                : "execute as " + name + " at @s run " + command;
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), full);
        }
    }
}
