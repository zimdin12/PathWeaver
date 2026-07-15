package dev.pathweaver.async;

import net.minecraft.world.level.pathfinder.Path;

import java.util.concurrent.Callable;
import java.util.function.Consumer;

/**
 * An immutable dispatch envelope. In 0.1.2 {@code search} still reads a live-backed region view and
 * live mob inputs; the envelope itself being immutable does not make those inputs snapshots.
 * {@code onDone} is invoked on the worker thread solely to enqueue the result — it must NEVER touch
 * the live world. Identity fields let the main-thread installer match the result to its entity and
 * decide staleness.
 */
public record PathRequest(RequestKey key, long dispatchTick,
                          Callable<Path> search, Consumer<Path> onDone) {}
