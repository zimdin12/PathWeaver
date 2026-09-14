package dev.pathweaver.frameprobe;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Logs client frame times and integrated-server tick times every five seconds, as one line each:
 *
 * <pre>[PWPROBE] frames wall=... n=... p50=... p95=... p99=... max=... ms over>50ms=...
 * [PWPROBE] ticks  wall=... n=... ...</pre>
 *
 * <p>A frame is one call of {@code Minecraft.runTick}, timed head to head, so it includes everything
 * the render thread waits on. A tick is {@code START_SERVER_TICK} to {@code END_SERVER_TICK}: the work
 * of the tick, not the sleep between ticks. Wall is epoch milliseconds, so lines can be matched to the
 * driver's phase markers.
 */
public final class FrameProbe implements ModInitializer {
    static final Logger LOG = LoggerFactory.getLogger("PWPROBE");
    static final long REPORT_NANOS = 5_000_000_000L;

    private final Window ticks = new Window();
    private final ScenarioScript scenario = new ScenarioScript();
    private long tickStart;
    private long lastTickReport = System.nanoTime();

    @Override
    public void onInitialize() {
        ServerTickEvents.START_SERVER_TICK.register(server -> tickStart = System.nanoTime());
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            long now = System.nanoTime();
            ticks.add(now - tickStart);
            scenario.tick(server);
            if (now - lastTickReport >= REPORT_NANOS) {
                int entities = 0;
                for (var level : server.getAllLevels()) {
                    for (var ignored : level.getAllEntities()) entities++;
                }
                LOG.info("[PWPROBE] ticks  wall={} entities={} {}", System.currentTimeMillis(), entities, ticks.drain());
                LOG.info("[PWPROBE] paths  {}", PathCalls.drain());
                lastTickReport = now;
            }
        });
    }

    /** Called by the client mixin at the head of every frame. */
    public static final class Frames {
        private static final Window FRAMES = new Window();
        private static long last;
        private static long lastReport;

        private Frames() {}

        public static void frame() {
            long now = System.nanoTime();
            if (last != 0) FRAMES.add(now - last);
            last = now;
            if (lastReport == 0) lastReport = now;
            if (now - lastReport >= REPORT_NANOS) {
                LOG.info("[PWPROBE] frames wall={} {}", System.currentTimeMillis(), FRAMES.drain());
                lastReport = now;
            }
        }
    }
}
