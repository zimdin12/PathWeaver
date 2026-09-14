package dev.pathweaver.frameprobe;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Who asks for paths, and how often: counts every entry to the innermost
 * {@code PathNavigation.createPath(Set, int, boolean, int, float)} on the server thread, and every
 * 32nd call is attributed to its mob type and to the first caller outside the navigation classes.
 *
 * <p>Measurement only, and present in every arm, so the same calls are counted with and without
 * PathWeaver. Server thread only: the method is only ever entered there.
 */
public final class PathCalls {
    private static final int SAMPLE_EVERY = 32;
    private static final AtomicLong CALLS = new AtomicLong();
    private static final Map<String, Integer> SAMPLED = new HashMap<>();
    private static final StackWalker WALKER = StackWalker.getInstance();

    private PathCalls() {}

    public static void entered(Object mob) {
        long n = CALLS.incrementAndGet();
        if (n % SAMPLE_EVERY != 0) return;
        String caller = WALKER.walk(frames -> frames
            .map(f -> f.getClassName().substring(f.getClassName().lastIndexOf('.') + 1) + "." + f.getMethodName())
            .filter(s -> !s.startsWith("PathNavigation") && !s.startsWith("GroundPathNavigation")
                && !s.startsWith("PathCalls") && !s.contains("pwprobe$") && !s.contains("pathweaver$")
                && !s.contains("Navigation.") && !s.startsWith("PathWeaver") && !s.startsWith("MinecraftFrame"))
            .findFirst().orElse("?"));
        String key = mob.getClass().getSimpleName() + " <- " + caller;
        SAMPLED.merge(key, 1, Integer::sum);
    }

    /** One line: the call count for the window and the top callers, then resets. */
    static String drain() {
        long calls = CALLS.getAndSet(0);
        StringBuilder sb = new StringBuilder("calls=").append(calls);
        SAMPLED.entrySet().stream()
            .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
            .limit(10)
            .forEach(e -> sb.append(" | ").append(e.getValue() * SAMPLE_EVERY).append(' ').append(e.getKey()));
        SAMPLED.clear();
        return sb.toString();
    }
}
