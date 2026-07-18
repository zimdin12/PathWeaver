package dev.pathweaver.gate;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.security.CodeSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MobOriginGateTest {

    @Test
    void representativeVanillaConcreteMobIsAllowedAndCacheIsStable() {
        assertTrue(MobOriginGate.isAllowed(Zombie.class, false));
        assertTrue(MobOriginGate.isAllowed(Zombie.class, false));
    }

    @Test
    void modDefinedMobSubclassIsDeniedByDefault() {
        assertFalse(MobOriginGate.isAllowed(TestModMob.class, false));
    }

    @Test
    void explicitUnsafeToggleBypassesOnlyTheOriginDecision() {
        assertTrue(MobOriginGate.isAllowed(TestModMob.class, true));
    }

    @Test
    void missingOrDifferentCodeSourceFailsClosed() throws Exception {
        CodeSource minecraft = new CodeSource(URI.create("file:/minecraft.jar").toURL(),
            (java.security.cert.Certificate[]) null);
        CodeSource mod = new CodeSource(URI.create("file:/mod.jar").toURL(),
            (java.security.cert.Certificate[]) null);

        assertFalse(MobOriginGate.originsMatch(null, minecraft));
        assertFalse(MobOriginGate.originsMatch(minecraft, null));
        assertFalse(MobOriginGate.originsMatch(minecraft, mod));
        assertTrue(MobOriginGate.originsMatch(minecraft, minecraft));
    }

    abstract static class TestModMob extends Mob {
        protected TestModMob(EntityType<? extends Mob> type, Level level) {
            super(type, level);
        }
    }
}
