package dev.pathweaver.gate;

import net.minecraft.world.entity.Mob;

import java.security.CodeSource;

/**
 * Allows mod-defined Mob subclasses to remain synchronous unless the user explicitly opts into their
 * unverified virtual behavior. Fabric's Knot loader preserves each class's defining CodeSource even
 * though Minecraft and mod classes share the loader.
 */
public final class MobOriginGate {
    private static final CodeSource MINECRAFT_ORIGIN = codeSource(Mob.class);
    private static final ClassValue<Boolean> VANILLA_ORIGIN = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            return Mob.class.isAssignableFrom(type)
                && originsMatch(MINECRAFT_ORIGIN, codeSource(type));
        }
    };

    private MobOriginGate() {}

    public static boolean isAllowed(Class<?> concreteClass, boolean allowModdedMobAsync) {
        return allowModdedMobAsync || VANILLA_ORIGIN.get(concreteClass);
    }

    static boolean originsMatch(CodeSource minecraft, CodeSource candidate) {
        return minecraft != null && candidate != null
            && minecraft.getLocation() != null
            && minecraft.getLocation().equals(candidate.getLocation());
    }

    private static CodeSource codeSource(Class<?> type) {
        try {
            var domain = type.getProtectionDomain();
            return domain == null ? null : domain.getCodeSource();
        } catch (SecurityException ignored) {
            return null;
        }
    }
}
