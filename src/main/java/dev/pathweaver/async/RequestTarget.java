package dev.pathweaver.async;

import java.util.Objects;
import java.util.Set;

/** Immutable semantic identity of a createPath request, used to detect target supersession. */
public record RequestTarget(Set<?> targets, int regionOffset, boolean offsetUpward,
                            int reachRange, int followRangeBits) {
    public RequestTarget {
        targets = Set.copyOf(Objects.requireNonNull(targets, "targets"));
    }

    public static RequestTarget of(Set<?> targets, int regionOffset, boolean offsetUpward,
                                   int reachRange, float followRange) {
        return new RequestTarget(targets, regionOffset, offsetUpward, reachRange,
            Float.floatToIntBits(followRange));
    }
}
