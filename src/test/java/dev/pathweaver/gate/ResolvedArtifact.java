package dev.pathweaver.gate;

import com.google.gson.JsonParser;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipFile;

/**
 * Which audited artifact a build actually resolved, and whether that is what this branch expects.
 *
 * <p>Every audit in this package pins one exact third-party artifact by SHA-256 and proves a shape
 * against its bytes. The tests used to assert flatly that the pinned bundle passes, which is a
 * statement about the build rather than about the audit, and is false on any branch resolving a
 * different version. Asserting the verdict in both directions fixes that.
 *
 * <p>But the arm must not be chosen by the artifact alone. If it is, bumping a dependency swaps
 * every audit test from proving the shape to merely proving a refusal, and the suite stays green
 * having verified nothing. The expected resolution therefore lives in
 * {@code src/test/resources/audited-artifacts.expected}, which is checked in per branch: a version
 * bump goes red until someone edits that file on purpose.
 */
final class ResolvedArtifact {
    private ResolvedArtifact() {}

    private static final String EXPECTATIONS = "/audited-artifacts.expected";

    /** The jar {@code type} was loaded from. */
    static Path jarOf(Class<?> type) throws Exception {
        return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());
    }

    /** The {@code version} declared in the jar's {@code fabric.mod.json}. */
    static String version(Path jar) throws Exception {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            var entry = zip.getEntry("fabric.mod.json");
            if (entry == null) throw new IllegalStateException("no fabric.mod.json in " + jar);
            try (InputStream in = zip.getInputStream(entry);
                 var reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                var version = JsonParser.parseReader(reader).getAsJsonObject().get("version");
                if (version == null) throw new IllegalStateException("no version in " + jar);
                return version.getAsString();
            }
        }
    }

    /**
     * True when this build resolved the pinned artifact -- and a hard failure when that disagrees
     * with what this branch declared.
     *
     * <p>This is the guard that stops a dependency bump from quietly turning every shape proof into
     * a refusal check. It fails closed: an unreadable or unlisted expectation is an error, never a
     * default.
     */
    static boolean isPinnedAsExpected(String moduleId, Class<?> probe, String pinnedVersion)
            throws Exception {
        return isPinnedAsExpected(moduleId, jarOf(probe), pinnedVersion);
    }

    /** As above, for a caller that already located the artifact. */
    static boolean isPinnedAsExpected(String moduleId, Path jar, String pinnedVersion)
            throws Exception {
        String resolved = version(jar);
        boolean pinned = pinnedVersion.equals(resolved);
        String declared = expectations().get(moduleId);
        if (declared == null) {
            throw new AssertionError(moduleId + " is not listed in " + EXPECTATIONS
                + "; an audited artifact with no declared expectation is not covered by anything");
        }
        boolean wantPinned = switch (declared) {
            case "pinned" -> true;
            case "unpinned" -> false;
            default -> throw new AssertionError(
                EXPECTATIONS + " gives " + moduleId + " the unknown value '" + declared
                    + "'; only 'pinned' and 'unpinned' mean anything");
        };
        if (pinned != wantPinned) {
            throw new AssertionError(String.format(
                "%s: this branch declares '%s' in %s but resolved %s (pinned is %s). Either the "
                    + "dependency changed, in which case the audit no longer covers what ships and "
                    + "that file must be edited deliberately, or the pin is wrong. It is not "
                    + "something to let pass silently -- doing so leaves the audit tests green over "
                    + "no shape coverage at all.",
                moduleId, declared, EXPECTATIONS, resolved, pinnedVersion));
        }
        return pinned;
    }

    private static Map<String, String> expectations() throws Exception {
        var url = ResolvedArtifact.class.getResource(EXPECTATIONS);
        if (url == null) throw new IllegalStateException("missing " + EXPECTATIONS);
        Map<String, String> out = new HashMap<>();
        for (String line : Files.readAllLines(Path.of(url.toURI()), StandardCharsets.UTF_8)) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            int eq = trimmed.indexOf('=');
            if (eq < 0) throw new IllegalStateException("unparsable line in " + EXPECTATIONS
                + ": " + line);
            out.put(trimmed.substring(0, eq).strip(), trimmed.substring(eq + 1).strip());
        }
        return out;
    }
}
