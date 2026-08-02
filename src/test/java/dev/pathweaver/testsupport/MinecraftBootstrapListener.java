package dev.pathweaver.testsupport;

import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;

/**
 * Boot Minecraft's registries once, before any test class runs.
 *
 * <p>This exists because seven tests were silently skipping every build for as long as anyone had
 * been counting them. {@code CertifiedLandProvidersTest} needs real {@code Blocks}, and booted them
 * in its own {@code @BeforeAll} behind an {@code Assumptions.assumeTrue}, on the reasoning that a
 * red suite depending on class-initialisation order teaches people to ignore red. The reasoning was
 * sound and the outcome was worse than red: run alone the class passed 7/7, run in the suite it
 * skipped 7/7, and the suite reported success either way.
 *
 * <p>The actual failure was {@code NoClassDefFoundError: Could not initialize class ... Blocks} —
 * "could not initialize", not "not found". Some earlier test class touched {@code Blocks} before
 * {@code Bootstrap.bootStrap()} had run, its static initialiser threw, and the JVM poisons a class
 * whose initialiser failed for the rest of the process. No {@code @BeforeAll} can recover from that,
 * because by the time it runs the damage is done. It has to happen before the first test class is
 * loaded, which is what a session listener is for.
 *
 * <p>Registered by ServiceLoader through
 * {@code META-INF/services/org.junit.platform.launcher.LauncherSessionListener}.
 *
 * <p>Deliberately does NOT swallow failures into a skip. If Minecraft cannot boot in the test JVM
 * that is a real problem with the build, and the tests that depend on it should go red rather than
 * quietly evaporate — which is the whole lesson of the seven.
 */
public final class MinecraftBootstrapListener implements LauncherSessionListener {

    private static volatile boolean booted;

    @Override
    public void launcherSessionOpened(LauncherSession session) {
        bootOnce();
    }

    /** Idempotent, and safe to call from a test that wants to be explicit about the dependency. */
    public static void bootOnce() {
        if (booted) return;
        synchronized (MinecraftBootstrapListener.class) {
            if (booted) return;
            try {
                net.minecraft.SharedConstants.tryDetectVersion();
            } catch (Throwable alreadyDetected) {
                // Detecting twice is not an error and must not stop the bootstrap below.
            }
            net.minecraft.server.Bootstrap.bootStrap();
            // Force the class whose initialiser is the one that actually matters, so a failure
            // surfaces here with a real stack trace rather than as a poisoned class much later.
            net.minecraft.world.level.block.Blocks.STONE.getStateDefinition().getPossibleStates();
            booted = true;
        }
    }
}
