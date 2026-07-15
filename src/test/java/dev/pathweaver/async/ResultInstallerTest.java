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
        final List<RequestKey> noPaths = new ArrayList<>();
        final List<RequestKey> failures = new ArrayList<>();
        FakeSink(Set<RequestKey> stale) { this.stale = stale; }
        public boolean isStale(RequestKey key, long t, double x, double y, double z) {
            return stale.contains(key);
        }
        public void install(RequestKey key, Path path) { installed.add(key); }
        public void discard(RequestKey key) { discarded.add(key); }
        public void noPath(RequestKey key) { noPaths.add(key); }
        public void failed(RequestKey key, Throwable failure) { failures.add(key); }
    }

    @Test void successInstallsFreshAndDiscardsStaleWithExactKeys() {
        ResultInstaller installer = new ResultInstaller();
        RequestKey fresh = key(1L, 1L, 1);
        RequestKey stale = key(1L, 2L, 2);
        installer.enqueue(fresh, 0L, PathOutcome.success(DUMMY), 0, 0, 0);
        installer.enqueue(stale, 0L, PathOutcome.success(DUMMY), 0, 0, 0);
        FakeSink sink = new FakeSink(Set.of(stale));
        installer.drain(sink);
        assertEquals(List.of(fresh), sink.installed);
        assertEquals(List.of(stale), sink.discarded);
        assertTrue(sink.noPaths.isEmpty());
        assertTrue(sink.failures.isEmpty());
    }

    @Test void vanillaNullRoutesToNoPathNotFailureOrCooldownPath() {
        ResultInstaller installer = new ResultInstaller();
        RequestKey noPath = key(1L, 5L, 5);
        installer.enqueue(noPath, 0L, PathOutcome.noPath(), 0, 0, 0);
        FakeSink sink = new FakeSink(Set.of());
        installer.drain(sink);
        assertEquals(List.of(noPath), sink.noPaths);
        assertTrue(sink.failures.isEmpty());
        assertTrue(sink.discarded.isEmpty());
        assertTrue(sink.installed.isEmpty());
    }

    @Test void workerThrowableRoutesOnlyToFailedWithExactCause() {
        ResultInstaller installer = new ResultInstaller();
        RequestKey failed = key(1L, 6L, 6);
        IllegalStateException cause = new IllegalStateException("boom");
        installer.enqueue(failed, 0L, PathOutcome.failed(cause), 0, 0, 0);
        List<Throwable> causes = new ArrayList<>();
        FakeSink sink = new FakeSink(Set.of()) {
            @Override public void failed(RequestKey key, Throwable failure) {
                super.failed(key, failure);
                causes.add(failure);
            }
        };
        installer.drain(sink);
        assertEquals(List.of(failed), sink.failures);
        assertEquals(List.of(cause), causes);
        assertTrue(sink.noPaths.isEmpty());
        assertTrue(sink.discarded.isEmpty());
    }

    @Test void drainDeliversEachResultOnce() {
        ResultInstaller installer = new ResultInstaller();
        RequestKey key = key(1L, 1L, 1);
        installer.enqueue(key, 0L, PathOutcome.success(DUMMY), 0, 0, 0);
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
