package dev.pathweaver.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Nothing a dedicated server loads may name a Cloth GUI class.
 *
 * <p>This exists because of a specific failure that no ordinary test could see. Cloth Config was
 * demoted from a required dependency to a suggested one, the whole suite went green, and the mod
 * still would not start on a server without Cloth: {@code CompatibilityTier} and
 * {@code PathCacheMode} implemented {@code SelectionListEntry.Translatable}, so initialising
 * {@code PathWeaverConfig}, which has fields of those types, resolved a GUI class and threw
 * {@code NoClassDefFoundError} before the server finished booting.
 *
 * <p>Every unit test passed throughout, and they always will, because the test classpath has Cloth on
 * it. The only thing that found it was booting a real server without the library. This test is the
 * cheap standing version of that boot: it reads the compiled bytes and refuses a Cloth reference in
 * any class the server touches.
 *
 * <p>What is deliberately allowed: {@code me/shedaniel/autoconfig/annotation}. Those are annotations,
 * and the JVM drops an annotation whose type it cannot resolve rather than failing. They are kept
 * because the settings screen and its contract test are both built from them.
 */
class NoClothOnTheServerPathTest {

    /** GUI classes. Naming one of these from the server path is the fault. */
    private static final String FORBIDDEN = "me/shedaniel/clothconfig2";

    /** Annotations only. Unresolvable annotations are dropped by the JVM, so these are harmless. */
    private static final String ALLOWED_ANNOTATIONS = "me/shedaniel/autoconfig/annotation";

    /**
     * The classes a dedicated server is guaranteed to load. Not a survey of the whole mod: these are
     * the ones on the path from the mod's entry point to reading its settings, which is where the
     * failure was and where it would be again.
     */
    private static final List<Class<?>> SERVER_PATH = List.of(
        PathWeaverConfig.class,
        ConfigFile.class,
        ConfigLoad.class,
        CompatibilityTier.class,
        PathCacheMode.class,
        dev.pathweaver.PathWeaver.class,
        dev.pathweaver.lod.RecomputeThrottle.class);

    private static byte[] bytesOf(Class<?> type) throws IOException {
        String resource = "/" + type.getName().replace('.', '/') + ".class";
        try (InputStream in = NoClothOnTheServerPathTest.class.getResourceAsStream(resource)) {
            assertNotNull(in, type.getName() + " is not on the test classpath");
            return in.readAllBytes();
        }
    }

    @Test
    void noServerClassNamesAClothGuiType() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Class<?> type : SERVER_PATH) {
            // The constant pool is searched as raw bytes rather than parsed. Class names appear in it
            // as UTF-8 regardless of whether they are a superinterface, a field type, a method
            // descriptor or a cast, and any of those resolves at load time.
            String raw = new String(bytesOf(type), StandardCharsets.ISO_8859_1);
            if (raw.contains(FORBIDDEN)) offenders.add(type.getName());
        }
        assertEquals(List.of(), offenders,
            "these classes load on a dedicated server and name a Cloth GUI type, which is a "
                + "NoClassDefFoundError on any server without Cloth installed: " + offenders);
    }

    /**
     * The positive control, and it is not optional.
     *
     * <p>The check above passes trivially if the search never finds anything, for instance if the
     * string were misspelled or the bytes were read wrong. {@code ClothScreen} genuinely does name
     * Cloth types and is genuinely never loaded on a server, so it must be found.
     */
    @Test
    void theScreenClassIsFoundByTheSameSearch() throws IOException {
        String raw = new String(bytesOf(ClothScreen.class), StandardCharsets.ISO_8859_1);
        assertTrue(raw.contains(FORBIDDEN),
            "the search cannot find a Cloth reference in the one class that certainly has one, so "
                + "its silence about the others means nothing");
    }

    /**
     * ModMenu's entry point must not name a Cloth type either.
     *
     * <p>It is loaded whenever ModMenu is installed, including on setups with no Cloth, and it is the
     * class that decides whether Cloth is present. A Cloth type in it would be resolved at exactly
     * the moment it was trying to find out whether that was safe.
     */
    @Test
    void theModMenuEntryPointNamesNoClothType() throws IOException {
        String raw = new String(bytesOf(PathWeaverModMenu.class), StandardCharsets.ISO_8859_1);
        assertTrue(!raw.contains(FORBIDDEN),
            "PathWeaverModMenu names a Cloth GUI type, so asking whether Cloth is installed would "
                + "itself require Cloth to be installed");
    }

    /** The annotations stay, and stay allowed. Removing them would empty the settings screen. */
    @Test
    void theConfigStillCarriesItsAnnotations() throws IOException {
        String raw = new String(bytesOf(PathWeaverConfig.class), StandardCharsets.ISO_8859_1);
        assertTrue(raw.contains(ALLOWED_ANNOTATIONS),
            "the settings annotations are gone; the screen is built by reflecting over them and "
                + "would now be empty");
    }
}
