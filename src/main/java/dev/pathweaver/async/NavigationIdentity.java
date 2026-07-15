package dev.pathweaver.async;

import dev.pathweaver.duck.PWNavigation;

import java.util.Objects;

/** Main-thread identity snapshot used to reject completions after entity/navigation/world/intent changes. */
public record NavigationIdentity(Object entityUuid, Object worldIdentity, Object dimensionIdentity,
                                 PWNavigation navigation, Object pathIdentity, long targetRevision) {
    public NavigationIdentity {
        Objects.requireNonNull(entityUuid, "entityUuid");
        Objects.requireNonNull(worldIdentity, "worldIdentity");
        Objects.requireNonNull(dimensionIdentity, "dimensionIdentity");
        Objects.requireNonNull(navigation, "navigation");
    }

    /** World and navigation are identity-sensitive; UUID/dimension are stable value identities. */
    public boolean sameLiveIdentity(NavigationIdentity current) {
        return current != null
            && entityUuid.equals(current.entityUuid)
            && worldIdentity == current.worldIdentity
            && dimensionIdentity.equals(current.dimensionIdentity)
            && navigation == current.navigation
            && pathIdentity == current.pathIdentity
            && targetRevision == current.targetRevision;
    }
}
