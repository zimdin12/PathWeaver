package dev.pathweaver.config;

import java.nio.file.Path;

/**
 * Load the settings file once and say plainly what happened.
 *
 * <p>Replaces the pair of Cloth adapters that used to do this: a serializer wrapper that remembered a
 * failed read, and AutoConfig's registration around it. Both existed only to communicate one bit —
 * did the load fail — through an API that answers by substituting defaults and saying nothing.
 *
 * <p>That bit matters. A failed load means the settings on disk are not the settings in force, and
 * the mod's response is to run synchronously until someone saves a valid file. Losing it would leave
 * a server quietly doing the opposite of what its config says.
 *
 * <p>A result object rather than an out-parameter: the old shape passed an {@code AtomicBoolean} in
 * and read it afterwards, which works and hides the fact that the load returns two things.
 */
public final class ConfigLoad {

    /** What a load produced, and whether the file behind it was usable. */
    public record Result(PathWeaverConfig config, boolean failed, Throwable cause) {

        /** The settings on disk were read and are the settings in force. */
        static Result ok(PathWeaverConfig config) {
            return new Result(config, false, null);
        }

        /**
         * The file could not be read. The config carried here is defaults, NOT what is on disk, and
         * the caller is expected to say so in the log and fall back to synchronous behaviour.
         */
        static Result failed(PathWeaverConfig defaults, Throwable cause) {
            return new Result(defaults, true, cause);
        }
    }

    private ConfigLoad() { }

    public static Result from(Path path) {
        return from(new ConfigFile(path));
    }

    public static Result from(ConfigFile file) {
        try {
            return Result.ok(file.deserialize());
        } catch (ConfigFile.ConfigIoException | RuntimeException failure) {
            // Defaults are produced WITHOUT touching the file again. Re-reading here to "try once
            // more" is how a transient failure becomes an inconsistent pair, where the returned
            // config and the failure flag describe two different states of the disk.
            return Result.failed(new PathWeaverConfig(), failure);
        }
    }
}
