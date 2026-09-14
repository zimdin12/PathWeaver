package dev.pathweaver.frameprobe;

import java.util.Arrays;

/**
 * Durations collected on one thread over a reporting window, summarised as percentiles.
 *
 * <p>Single-writer: each instance is only touched by the thread whose durations it records, so it
 * needs no synchronisation. The summary is computed and the window cleared in the same call.
 */
final class Window {
    private long[] nanos = new long[4096];
    private int count;

    void add(long durationNanos) {
        if (count == nanos.length) nanos = Arrays.copyOf(nanos, count * 2);
        nanos[count++] = durationNanos;
    }

    int count() {
        return count;
    }

    /** "n=.. p50=.. p95=.. p99=.. max=.. ms" and empties the window. */
    String drain() {
        if (count == 0) return "n=0";
        long[] sorted = Arrays.copyOf(nanos, count);
        Arrays.sort(sorted);
        long total = 0;
        for (long v : sorted) total += v;
        String out = String.format(java.util.Locale.ROOT,
            "n=%d mean=%.2f p50=%.2f p95=%.2f p99=%.2f max=%.2f ms over>50ms=%d",
            count, total / (double) count / 1e6, pct(sorted, 0.50), pct(sorted, 0.95), pct(sorted, 0.99),
            sorted[count - 1] / 1e6, over(sorted, 50_000_000L));
        count = 0;
        return out;
    }

    private static double pct(long[] sorted, double q) {
        int i = (int) Math.min(sorted.length - 1, Math.floor(q * (sorted.length - 1)));
        return sorted[i] / 1e6;
    }

    private static int over(long[] sorted, long threshold) {
        int n = 0;
        for (long v : sorted) if (v > threshold) n++;
        return n;
    }
}
