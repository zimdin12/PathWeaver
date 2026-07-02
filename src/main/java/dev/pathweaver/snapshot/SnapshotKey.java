package dev.pathweaver.snapshot;

/** Identity of a per-tick shared snapshot: same dimension + tick + region bounds => reuse one build. */
public record SnapshotKey(String dimensionId, long tick,
                          int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ) {}
