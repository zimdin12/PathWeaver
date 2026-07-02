package dev.pathweaver.elision;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RepathToleranceTest {
    @Test void reusesWithinTolerance() {
        assertTrue(RepathTolerance.canReuseExistingPath(new BlockPos(0,0,0), new BlockPos(1,0,0), 1));
        assertFalse(RepathTolerance.canReuseExistingPath(new BlockPos(0,0,0), new BlockPos(3,0,0), 1));
    }
    @Test void nullTargetsForceRepath() {
        assertFalse(RepathTolerance.canReuseExistingPath(null, new BlockPos(0,0,0), 1));
        assertFalse(RepathTolerance.canReuseExistingPath(new BlockPos(0,0,0), null, 1));
    }
    @Test void toleranceZeroMatchesVanillaExactOnly() {
        assertTrue(RepathTolerance.canReuseExistingPath(new BlockPos(2,7,3), new BlockPos(2,7,3), 0));
        assertFalse(RepathTolerance.canReuseExistingPath(new BlockPos(2,7,3), new BlockPos(2,7,4), 0));
    }
    @Test void anyWithinToleranceScansSet() {
        BlockPos old = new BlockPos(10,64,10);
        assertTrue(RepathTolerance.anyWithinTolerance(
            Set.of(new BlockPos(50,64,50), new BlockPos(11,64,10)), old, 1));
        assertFalse(RepathTolerance.anyWithinTolerance(
            Set.of(new BlockPos(50,64,50), new BlockPos(20,64,20)), old, 1));
        assertFalse(RepathTolerance.anyWithinTolerance(Set.of(new BlockPos(11,64,10)), null, 1));
    }
}
