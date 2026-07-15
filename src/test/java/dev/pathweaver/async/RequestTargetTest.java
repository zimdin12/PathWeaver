package dev.pathweaver.async;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RequestTargetTest {
    @Test void targetSetIsDefensivelyCopied() {
        Set<Object> mutable = new HashSet<>(Set.of("a"));
        RequestTarget target = RequestTarget.of(mutable, 8, false, 1, 32.0F);
        mutable.add("b");
        assertEquals(Set.of("a"), target.targets());
        assertThrows(UnsupportedOperationException.class, target.targets()::clear);
    }

    @Test void everySearchShapingFieldParticipatesInEquality() {
        RequestTarget base = RequestTarget.of(Set.of("a"), 8, false, 1, 32.0F);
        assertNotEquals(base, RequestTarget.of(Set.of("b"), 8, false, 1, 32.0F));
        assertNotEquals(base, RequestTarget.of(Set.of("a"), 9, false, 1, 32.0F));
        assertNotEquals(base, RequestTarget.of(Set.of("a"), 8, true, 1, 32.0F));
        assertNotEquals(base, RequestTarget.of(Set.of("a"), 8, false, 2, 32.0F));
        assertNotEquals(base, RequestTarget.of(Set.of("a"), 8, false, 1, 33.0F));
        assertEquals(base, RequestTarget.of(Set.of("a"), 8, false, 1, 32.0F));
    }
}
