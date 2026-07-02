package dev.pathweaver.async;

import net.minecraft.world.level.pathfinder.Path;

import java.util.concurrent.Callable;
import java.util.function.Consumer;

/**
 * An immutable unit of off-thread work. {@code search} runs the A* on the read-only snapshot only;
 * {@code onDone} is invoked on the worker thread solely to enqueue the result — it must NEVER touch
 * the live world. Identity fields let the main-thread installer match the result to its entity and
 * decide staleness.
 */
public record PathRequest(int entityId, long dispatchTick,
                          Callable<Path> search, Consumer<Path> onDone) {}
