package dev.pathweaver.async;

import net.minecraft.world.level.pathfinder.Path;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ResultInstallerTest {
    static class FakeSink implements ResultInstaller.InstallSink {
        final Set<RequestKey> stale;
        final List<RequestKey> installed = new ArrayList<>();
        final List<RequestKey> discarded = new ArrayList<>();
        FakeSink(Set<RequestKey> stale) { this.stale = stale; }
        public boolean isStale(RequestKey key, long t, double x, double y, double z) {
            return stale.contains(key);
        }
        public void install(RequestKey key, Path path) { installed.add(key); }
        public void discard(RequestKey key) { discarded.add(key); }
    }

    @Test void installsFreshDiscardsStaleWithExactKeys() {
        ResultInstaller installer = new ResultInstaller();
        RequestKey fresh = key(1L, 1L, 1);
        RequestKey stale = key(1L, 2L, 2);
        installer.enqueue(fresh, 0L, DUMMY, 0, 0, 0);
        installer.enqueue(stale, 0L, DUMMY, 0, 0, 0);
        FakeSink sink = new FakeSink(Set.of(stale));
        installer.drain(sink);
        assertEquals(List.of(fresh), sink.installed);
        assertEquals(List.of(stale), sink.discarded);
    }

    @Test void nullPathIsDiscardedByDefaultFailureHandler() {
        ResultInstaller installer = new ResultInstaller();
        RequestKey key = key(1L, 5L, 5);
        installer.enqueue(key, 0L, null, 0, 0, 0);
        FakeSink sink = new FakeSink(Set.of());
        installer.drain(sink);
        assertEquals(List.of(key), sink.discarded);
        assertTrue(sink.installed.isEmpty());
    }

    @Test void nullRoutesToFailedNotDiscardWhenSinkDistinguishes() {
        ResultInstaller installer = new ResultInstaller();
        RequestKey failedKey = key(1L, 1L, 1);
        RequestKey staleKey = key(1L, 2L, 2);
        installer.enqueue(failedKey, 0L, null, 0, 0, 0);
        installer.enqueue(staleKey, 0L, DUMMY, 0, 0, 0);
        var failed = new ArrayList<RequestKey>();
        var discarded = new ArrayList<RequestKey>();
        ResultInstaller.InstallSink sink = new ResultInstaller.InstallSink() {
            public boolean isStale(RequestKey key, long t, double x, double y, double z) {
                return key.equals(staleKey);
            }
            public void install(RequestKey key, Path path) { fail("nothing should install"); }
            public void discard(RequestKey key) { discarded.add(key); }
            @Override public void failed(RequestKey key) { failed.add(key); }
        };
        installer.drain(sink);
        assertEquals(List.of(failedKey), failed);
        assertEquals(List.of(staleKey), discarded);
    }

    @Test void drainDeliversEachResultOnce() {
        ResultInstaller installer = new ResultInstaller();
        RequestKey key = key(1L, 1L, 1);
        installer.enqueue(key, 0L, DUMMY, 0, 0, 0);
        FakeSink sink = new FakeSink(Set.of());
        installer.drain(sink);
        installer.drain(sink);
        assertEquals(List.of(key), sink.installed);
        assertEquals(0, installer.pending());
    }

    private static RequestKey key(long epoch, long token, int entityId) {
        return new RequestKey(epoch, token, entityId);
    }

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
