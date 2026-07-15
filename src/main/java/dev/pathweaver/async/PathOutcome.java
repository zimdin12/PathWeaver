package dev.pathweaver.async;

import net.minecraft.world.level.pathfinder.Path;

import java.util.Objects;

/** Explicit terminal result of one worker search. A vanilla {@code null} path is NO_PATH, not FAILED. */
public record PathOutcome(Status status, Path path, Throwable failure) {
    public enum Status { SUCCESS, NO_PATH, FAILED }

    public PathOutcome {
        Objects.requireNonNull(status, "status");
        boolean valid = switch (status) {
            case SUCCESS -> path != null && failure == null;
            case NO_PATH -> path == null && failure == null;
            case FAILED -> path == null && failure != null;
        };
        if (!valid) throw new IllegalArgumentException("Contradictory path outcome: " + status);
    }

    public static PathOutcome success(Path path) {
        return new PathOutcome(Status.SUCCESS, Objects.requireNonNull(path, "path"), null);
    }

    public static PathOutcome noPath() {
        return new PathOutcome(Status.NO_PATH, null, null);
    }

    public static PathOutcome failed(Throwable failure) {
        return new PathOutcome(Status.FAILED, null, Objects.requireNonNull(failure, "failure"));
    }
}
