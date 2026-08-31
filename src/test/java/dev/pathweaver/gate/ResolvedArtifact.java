package dev.pathweaver.gate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.zip.ZipFile;

/**
 * The version of the mod artifact a build actually resolved onto the test classpath.
 *
 * <p>Every audit in this package pins one exact third-party artifact by SHA-256 and proves a shape
 * against its bytes. The tests then asserted, flatly, that the pinned bundle passes. That assertion
 * is a statement about the build, not about the audit, and it is only true on the branch whose
 * dependencies resolve the pinned versions. On the 26.2 port branch Gradle resolves newer ServerCore,
 * rabbit-pathfinding-fix and Fabric API modules, so five tests failed from the day the branch
 * existed, and 0.6.1+26.2 was published with the suite red.
 *
 * <p>Reading the resolved version lets each test pin the audit's verdict in BOTH directions: the
 * pinned artifact certifies with its exact shape proof, anything else refuses and names the drift.
 * That is a stronger contract than "it passes", and it holds on either branch.
 */
final class ResolvedArtifact {
    private ResolvedArtifact() {}

    /** The jar {@code type} was loaded from. */
    static Path jarOf(Class<?> type) throws Exception {
        return Path.of(new java.net.URI(
            type.getProtectionDomain().getCodeSource().getLocation().toString()));
    }

    /** The {@code version} declared in the jar's {@code fabric.mod.json}. */
    static String version(Path jar) throws Exception {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            var entry = zip.getEntry("fabric.mod.json");
            if (entry == null) throw new IllegalStateException("no fabric.mod.json in " + jar);
            String json;
            try (var in = zip.getInputStream(entry)) {
                json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            String key = quoted("version");
            int at = json.indexOf(key);
            if (at < 0) throw new IllegalStateException("no version key in " + jar);
            int open = json.indexOf('"', json.indexOf(':', at + key.length()) + 1);
            int close = json.indexOf('"', open + 1);
            if (open < 0 || close < 0) throw new IllegalStateException("unparsable version in " + jar);
            return json.substring(open + 1, close);
        }
    }

    /** True when the jar {@code type} came from declares exactly {@code pinned}. */
    static boolean isPinned(Class<?> type, String pinned) throws Exception {
        return pinned.equals(version(jarOf(type)));
    }

    private static String quoted(String s) { return '"' + s + '"'; }
}
