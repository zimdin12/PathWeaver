package dev.pathweaver.mixin;

import dev.pathweaver.async.PathWeaverThread;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What the provider-lookup redirect answers, on each kind of thread.
 *
 * <p>Off a worker it must be exactly Fabric's own {@code PATH_TYPES.get(block)}: the redirect sits on
 * every node of every synchronous search and may not change one answer. On a worker in a search it
 * must never read the live map, and must answer "no rule" for a block nobody certified.
 */
class LandProviderLookupRedirectTest {

    private static Object lookup(Map<?, ?> map, Object block) throws Exception {
        Method handler = LandPathTypeRegistryMixin.class.getDeclaredMethod(
            "pathweaver$keepWorkerOutOfLiveProviderMap", Map.class, Object.class);
        handler.setAccessible(true);
        return handler.invoke(null, map, block);
    }

    @Test
    void offAWorkerTheAnswerIsTheLiveMapsOwn() throws Exception {
        Object provider = new Object();
        assertSame(provider, lookup(Map.of(Blocks.STONE, provider), Blocks.STONE));
        assertNull(lookup(Map.of(Blocks.STONE, provider), Blocks.DIRT));
    }

    @Test
    void aWorkerInASearchNeverReadsTheLiveMap() throws Exception {
        Map<Object, Object> refusesReads = new java.util.AbstractMap<>() {
            @Override public java.util.Set<Entry<Object, Object>> entrySet() {
                throw new AssertionError("a worker read the live provider map");
            }
            @Override public Object get(Object key) {
                throw new AssertionError("a worker read the live provider map");
            }
        };
        AtomicReference<Object> answer = new AtomicReference<>("unset");
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new PathWeaverThread.Worker(() -> {
            PathWeaverThread.enterWorker();
            try {
                answer.set(lookup(refusesReads, Blocks.STONE));
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                PathWeaverThread.exitWorker();
            }
        }, "lookup-worker");
        worker.start();
        worker.join(5000);
        assertNull(failure.get(), () -> "worker lookup failed: " + failure.get());
        assertNull(answer.get(), "an uncertified block has no rule for a worker");
    }
}
