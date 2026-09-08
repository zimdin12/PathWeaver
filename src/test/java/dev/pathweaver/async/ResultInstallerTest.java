package dev.pathweaver.async;

import net.minecraft.world.level.pathfinder.Path;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ResultInstallerTest {

    /**
     * The live configuration generation, not a literal. The drain passes whatever
     * the published config's own generation, so a dispatch that remembered under a
     * different number would be discarded at the barrier. Reading the same source here makes this
     * test fail if dispatch and drain ever stop agreeing on which configuration they are under.
     */
    private static long gen() { return dev.pathweaver.config.PathWeaverConfig.get().generation(); }
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
        final List<RequestOutcome> reasons = new ArrayList<>();
        public void discard(RequestKey key, RequestOutcome reason) {
            discarded.add(key);
            reasons.add(reason);
        }
        final List<RequestKey> epilogues = new ArrayList<>();
        public void runEpilogue(RequestKey key) { epilogues.add(key); }
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

    /**
     * The seam between the two halves of the cache.
     *
     * <p>Dispatch remembers a key; the drain is the only place on the main thread that sees every
     * finished search with its result, so it is the only place that can fill the entry. Every
     * assertion in {@code PathCacheTest} drives the cache directly, so all of them stay green if
     * this call is deleted and nothing is ever cached in a running game. That is the shape of defect
     * this project has been bitten by before: a predicate everything asserts and a call site nothing
     * does.
     */
    @Test void aDrainedRouteReachesTheSharedCacheAndAFailedOneClearsItsSlot() {
        dev.pathweaver.cache.PathCache cache = dev.pathweaver.PathWeaverRuntime.get().resultCache();
        cache.clear();
        dev.pathweaver.cache.PathCacheKey cacheKey = new dev.pathweaver.cache.PathCacheKey(
            "overworld", 1, 2, 3,
            RequestTarget.of(Set.of(new net.minecraft.core.BlockPos(4, 5, 6)), 8, false, 1, 16.0f),
            String.class, Integer.class, 0, 4096, 0, 0, 0, 0, 0, 0, new int[] {1});

        ResultInstaller installer = new ResultInstaller();
        RequestKey landed = key(1L, 20L, 20);
        cache.remember(landed, cacheKey, 0L, 0L, 0L, 0L, gen());
        installer.enqueue(landed, 0L, PathOutcome.success(realPath()), 0, 0, 0);
        installer.drain(new FakeSink(Set.of()));
        assertEquals(1, cache.size(), "a finished route never reached the cache");

        // And the other direction: a search that produced nothing must not leave its slot behind.
        RequestKey empty = key(1L, 21L, 21);
        cache.remember(empty, cacheKey, 0L, 0L, 0L, 0L, gen());
        installer.enqueue(empty, 0L, PathOutcome.noPath(), 0, 0, 0);
        installer.drain(new FakeSink(Set.of()));
        assertEquals(1, cache.size(), "a no-path result was cached as if it were a route");
        cache.clear();
    }

    /** A real path, because the cache reads its nodes; {@code DUMMY} is an unconstructed shell. */
    private static Path realPath() {
        java.util.List<net.minecraft.world.level.pathfinder.Node> nodes = new ArrayList<>();
        nodes.add(new net.minecraft.world.level.pathfinder.Node(1, 2, 3));
        nodes.add(new net.minecraft.world.level.pathfinder.Node(2, 2, 3));
        return new Path(nodes, new net.minecraft.core.BlockPos(2, 2, 3), true);
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

    /**
     * A cancelled search is actually COUNTED, not silently dropped.
     *
     * <p>This is the test that was missing. The pre-flight cancel was routed through
     * {@code sink.discard}, which requires a live registration under that exact key -- and a
     * cancellation happens precisely when no such registration exists. So every cancellation
     * no-opped, the outcome was unreachable, and the row an operator was told to watch grow was
     * provably always zero.
     *
     * <p>It drives the real installer against a real sink with NOTHING registered, which is the
     * state a cancellation actually occurs in. The previous coverage handed the pool a literal
     * {@code () -> false} and never went through the sink at all, so it could not see this.
     */
    @Test
    void aCancelledSearchIsCountedEvenThoughNothingIsRegistered() {
        dev.pathweaver.PathWeaverRuntime runtime = dev.pathweaver.PathWeaverRuntime.get();
        long before = runtime.outcomeCount(RequestOutcome.CANCELLED_BEFORE_START);

        EntityInstallSink sink = new EntityInstallSink();
        ResultInstaller installer = new ResultInstaller();
        RequestKey key = new RequestKey(1L, 1L, 4242);

        installer.enqueue(key, 0L, PathOutcome.cancelled(), 0.0, 0.0, 0.0);
        installer.drain(sink);

        assertEquals(before + 1, runtime.outcomeCount(RequestOutcome.CANCELLED_BEFORE_START),
            "a cancellation must be recorded even with no registration -- that absence is exactly "
                + "what a cancellation means, and routing it through discard() recorded nothing");
    }
}
