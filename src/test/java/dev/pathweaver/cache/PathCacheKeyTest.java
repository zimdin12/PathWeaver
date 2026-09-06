package dev.pathweaver.cache;

import dev.pathweaver.async.RequestTarget;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The key has to notice every input it carries.
 *
 * <p>The bug this is aimed at is specific and quiet: someone adds a determinant to the record --
 * a new evaluator flag, a mob attribute a future version reads -- and does not add it to the
 * hand-written {@code equals}. The cache then answers a question with a route computed for a
 * different one, and the only symptom is a mob occasionally taking a route that was right for
 * something else. Nothing crashes and nobody files it.
 *
 * <p>So the components are walked rather than listed. A field added tomorrow is covered today.
 */
class PathCacheKeyTest {

    private static PathCacheKey base() {
        return new PathCacheKey("overworld", 10, 64, -20,
            RequestTarget.of(Set.of(new BlockPos(30, 64, 30)), 8, false, 1, 16.0f),
            String.class, Integer.class, 0b0101, 4096, Float.floatToIntBits(1.0f),
            Float.floatToIntBits(0.6f), 3,
            Float.floatToIntBits(0.6f), Float.floatToIntBits(1.95f), 1,
            new int[] {1, 2, 3});
    }

    @Test
    void twoKeysBuiltFromTheSameInputsMatchAndAgreeOnTheirHash() {
        // The positive control for every assertion below. An equals() that returned false for
        // everything would pass all the "differs" cases and leave the cache permanently empty,
        // which looks exactly like "mobs here never repeat a search".
        assertEquals(base(), base());
        assertEquals(base().hashCode(), base().hashCode());
    }

    @Test
    void changingAnySingleComponentMakesItADifferentKey() {
        RecordComponent[] components = PathCacheKey.class.getRecordComponents();
        // The walk itself needs proving: zero components would make this test vacuously green.
        assertTrue(components.length >= 16,
            "expected the key to carry at least 16 inputs, found " + components.length);
        for (int i = 0; i < components.length; i++) {
            PathCacheKey varied = withComponentChanged(i);
            assertNotEquals(base(), varied,
                "key ignores its own '" + components[i].getName() + "' component");
        }
    }

    /** Rebuilds the base key with component {@code index} replaced by a different value. */
    private static PathCacheKey withComponentChanged(int index) {
        RecordComponent[] components = PathCacheKey.class.getRecordComponents();
        List<Object> values = new ArrayList<>();
        for (int i = 0; i < components.length; i++) {
            Object original = read(components[i]);
            values.add(i == index ? somethingElse(original) : original);
        }
        try {
            Class<?>[] types = new Class<?>[components.length];
            for (int i = 0; i < components.length; i++) types[i] = components[i].getType();
            return PathCacheKey.class.getDeclaredConstructor(types)
                .newInstance(values.toArray());
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("could not rebuild the key", e);
        }
    }

    private static Object read(RecordComponent component) {
        try {
            return component.getAccessor().invoke(base());
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("could not read " + component.getName(), e);
        }
    }

    /** A value of the same type that is definitely not the one passed in. */
    private static Object somethingElse(Object original) {
        if (original instanceof Integer value) return value + 1;
        if (original instanceof int[] value) {
            int[] changed = value.clone();
            changed[0]++;
            return changed;
        }
        if (original instanceof Class<?>) return Double.class;
        if (original instanceof RequestTarget target) {
            return RequestTarget.of(Set.of(new BlockPos(31, 64, 30)), target.regionOffset(),
                target.offsetUpward(), target.reachRange(), 16.0f);
        }
        if (original instanceof String value) return value + "-nether";
        throw new AssertionError("no substitute defined for " + original.getClass()
            + "; a new component type needs one here or its case is silently unchecked");
    }
}
