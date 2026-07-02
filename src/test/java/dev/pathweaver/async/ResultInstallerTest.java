package dev.pathweaver.async;

import net.minecraft.world.level.pathfinder.Path;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ResultInstallerTest {
    static class FakeSink implements ResultInstaller.InstallSink {
        final Set<Integer> stale;
        final List<Integer> installed = new ArrayList<>();
        final List<Integer> discarded = new ArrayList<>();
        FakeSink(Set<Integer> stale) { this.stale = stale; }
        public boolean isStale(int id, long t, double x, double y, double z) { return stale.contains(id); }
        public void install(int id, Path p) { installed.add(id); }
        public void discard(int id) { discarded.add(id); }
    }

    @Test void installsFreshDiscardsStale() {
        ResultInstaller r = new ResultInstaller();
        r.enqueue(1, 0L, DUMMY, 0, 0, 0);
        r.enqueue(2, 0L, DUMMY, 0, 0, 0);
        FakeSink sink = new FakeSink(Set.of(2));
        r.drain(sink);
        assertEquals(List.of(1), sink.installed);
        assertEquals(List.of(2), sink.discarded);
    }

    @Test void nullPathIsDiscarded() {
        ResultInstaller r = new ResultInstaller();
        r.enqueue(5, 0L, null, 0, 0, 0);
        FakeSink sink = new FakeSink(Set.of());
        r.drain(sink);
        assertEquals(List.of(5), sink.discarded);
        assertTrue(sink.installed.isEmpty());
    }

    @Test void drainDeliversEachResultOnce() {
        ResultInstaller r = new ResultInstaller();
        r.enqueue(1, 0L, DUMMY, 0, 0, 0);
        FakeSink sink = new FakeSink(Set.of());
        r.drain(sink);
        r.drain(sink); // nothing left
        assertEquals(List.of(1), sink.installed);
        assertEquals(0, r.pending());
    }

    // A non-null Path sentinel; the installer only null-checks it, never dereferences it.
    // Allocate without invoking the constructor (avoids pulling in MC world internals).
    private static final Path DUMMY = dummyPath();
    private static Path dummyPath() {
        try {
            java.lang.reflect.Field f = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
            f.setAccessible(true);
            Object unsafe = f.get(null);
            java.lang.reflect.Method alloc = unsafe.getClass().getMethod("allocateInstance", Class.class);
            return (Path) alloc.invoke(unsafe, Path.class);
        } catch (Throwable t) {
            throw new RuntimeException("could not allocate dummy Path", t);
        }
    }
}
