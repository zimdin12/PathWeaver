package dev.pathweaver.async;

/**
 * Immutable identity for one async request. Entity IDs may be reused across server sessions, so a
 * completion is authoritative only when epoch, monotonically increasing request token, and entity ID
 * all match the registration that is still live on the main thread.
 */
public record RequestKey(long serverEpoch, long requestToken, int entityId) {
    public RequestKey {
        if (serverEpoch <= 0L) throw new IllegalArgumentException("serverEpoch must be positive");
        if (requestToken <= 0L) throw new IllegalArgumentException("requestToken must be positive");
    }
}
