package dev.pathweaver.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A failed read must stay visible as a failure.
 *
 * <p>Replaces LoadFailureTrackingSerializerTest. The wrapper it tested existed to smuggle one bit
 * past Cloth, which answered a failed load by substituting defaults and saying nothing. Cloth is
 * gone; the bit still matters, because a failed load means the settings on disk are not the settings
 * in force and the mod's response is to run synchronously until someone saves a valid file.
 *
 * <p>The failure mode this catches is a loader that returns defaults and reports success, which looks
 * identical to a clean first run and would leave a server doing the opposite of what its config says.
 */
class ConfigLoadTest {

    @Test
    void anUnreadableFileIsReportedAsFailedAndNotAsAFreshInstall(@TempDir Path tempDir) throws Exception {
        Path path = tempDir.resolve("pathweaver.json");
        Files.writeString(path, "{\"enabled\":\"yes\"}");

        ConfigLoad.Result result = ConfigLoad.from(path);

        assertTrue(result.failed(), "a malformed settings file was reported as a successful load");
        assertNotNull(result.cause(), "the failure was reported without saying what went wrong");
        assertNotNull(result.config(), "no config was returned to fall back to");
    }

    /** The positive control: a valid file loads and is NOT reported as a failure. */
    @Test
    void aValidFileLoadsCleanly(@TempDir Path tempDir) throws Exception {
        Path path = tempDir.resolve("pathweaver.json");
        Files.writeString(path, "{\"configVersion\":3,\"enabled\":false}");

        ConfigLoad.Result result = ConfigLoad.from(path);

        assertFalse(result.failed(), "a valid settings file was reported as a failed load");
        assertFalse(result.config().enabled, "the value on disk did not reach the loaded config");
    }

    /** A file that is simply absent is a first run, not a fault. */
    @Test
    void anAbsentFileIsAFirstRunRatherThanAFailure(@TempDir Path tempDir) {
        ConfigLoad.Result result = ConfigLoad.from(tempDir.resolve("pathweaver.json"));

        assertFalse(result.failed(), "a missing settings file was treated as a read failure");
        assertNotNull(result.config());
    }

    /**
     * A failed load forces the mod off, through the same publication path the runtime uses.
     *
     * <p>This is the consequence the bit exists for. Without it the mod would run asynchronously on
     * settings nobody verified.
     */
    @Test
    void publishingAFailedLoadForcesTheModOff(@TempDir Path tempDir) throws Exception {
        Path path = tempDir.resolve("pathweaver.json");
        Files.writeString(path, "{\"enabled\":\"yes\"}");
        ConfigLoad.Result result = ConfigLoad.from(path);
        PathWeaverConfig previous = PathWeaverConfig.get();
        try {
            PathWeaverConfig.publishLoaded(result.config(), result.failed());
            assertFalse(PathWeaverConfig.get().enabled,
                "a failed config load left the mod enabled");
        } finally {
            PathWeaverConfig.set(previous);
        }
    }
}
