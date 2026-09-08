package dev.pathweaver.config;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Nothing in production writes to a config after it has been published.
 *
 * <p>The cache's policy barrier only fires when the generation moves, and the generation moves only
 * in {@link PathWeaverConfig#set}. So a settings change that reaches the running config by any other
 * route is a change the cache never learns about: entries stored under the old policy stay, and are
 * served under the new one. That is the defect the barrier exists to prevent, arriving around it.
 *
 * <p>Publication copies now, so the settings screen's object is never the published one. That closes
 * the alias the review found. It does not stop someone writing {@code PathWeaverConfig.get().x = y}
 * in a future change, and a grep proving nobody does that today is out of date the moment it is
 * written down. This is that grep, kept honest by running on every build against the compiled
 * classes rather than the source text.
 *
 * <p>Two separate populations, and this file is explicit about which check covers which, because a
 * direct-write scan was mistaken for ownership closure once already:
 *
 * <ul>
 *   <li>DIRECT FIELD WRITES, {@code config.x = y}. Covered by the bytecode scan below. It compiles to
 *       a {@code PUTFIELD} on the config, which is what the scan looks for.</li>
 *   <li>CONTAINER MUTATION, {@code config.trustedMods.add(...)}. Invisible to that scan: there is no
 *       PUTFIELD on the config at all. Covered instead by prevention, in the test below it: the
 *       published snapshot's collections are unmodifiable, so that call throws rather than silently
 *       changing live settings without moving the generation.</li>
 * </ul>
 *
 * <p>Neither covers reflection, and neither covers tests, which do write through {@code get()} and
 * are why it still returns a mutable object. This is a bounded audit of production writers, not a
 * proof that a published snapshot is immutable in every sense.
 */
class PublishedConfigOwnershipTest {

    private static final String CONFIG = "dev/pathweaver/config/PathWeaverConfig";
    /** Production classes only. Tests write to the live config deliberately, and may. */
    private static final Path CLASSES = Path.of("build", "classes", "java", "main");

    @Test
    void noProductionClassWritesToAConfigItDidNotCreate() throws IOException {
        List<String> writers = new ArrayList<>();
        List<String> scanned = new ArrayList<>();

        try (Stream<Path> tree = Files.walk(CLASSES)) {
            for (Path file : tree.filter(p -> p.toString().endsWith(".class")).toList()) {
                ClassNode node = new ClassNode();
                new ClassReader(Files.readAllBytes(file)).accept(node, 0);
                scanned.add(node.name);
                // The config writes to itself: that is what the constructor, validatePostLoad and the
                // snapshot copy do, and they are the owner.
                if (node.name.equals(CONFIG)) continue;
                for (MethodNode method : node.methods) {
                    for (AbstractInsnNode insn : method.instructions) {
                        if (insn.getOpcode() != Opcodes.PUTFIELD) continue;
                        if (!(insn instanceof FieldInsnNode write)) continue;
                        if (!CONFIG.equals(write.owner)) continue;
                        writers.add(node.name + "." + method.name + " writes " + write.name);
                    }
                }
            }
        }

        // Positive control on the walk: a scan that found no classes would report no writers and
        // prove nothing. The production tree is not small.
        assertTrue(scanned.size() > 50,
            "the class walk found only " + scanned.size() + " classes; it is not scanning production");
        assertTrue(scanned.contains(CONFIG),
            "the config class itself was not scanned, so this is looking in the wrong place");

        assertEquals(List.of(), writers,
            "a production class writes to a config without republishing it, so the cache never learns "
                + "the policy moved: " + writers);
    }

    /**
     * The other population: a published snapshot's collections cannot be mutated in place.
     *
     * <p>{@code PathWeaverConfig.get().trustedMods.add("x")} changes live settings and compiles to no
     * field write at all, so the scan above cannot see it and never could. Sealing the published
     * copy turns it into an exception instead. The one production reader copies the list into a Set,
     * so nothing loses anything by this.
     */
    @Test
    void aPublishedSnapshotsCollectionsCannotBeMutatedInPlace() {
        PathWeaverConfig editor = new PathWeaverConfig();
        editor.trustedMods = new java.util.ArrayList<>(java.util.List.of("some-mod"));
        PathWeaverConfig.set(editor);

        PathWeaverConfig live = PathWeaverConfig.get();
        assertEquals(java.util.List.of("some-mod"), live.trustedMods,
            "the published snapshot did not carry the list, so sealing it proves nothing");
        assertThrows(UnsupportedOperationException.class, () -> live.trustedMods.add("sneaked-in"),
            "a published snapshot's list can be added to, which changes live settings without "
                + "moving the generation the cache watches");

        // The editor's own list stays writable: it is the caller's object and the settings screen
        // has to be able to edit it.
        editor.trustedMods.add("added-later");
        assertEquals(java.util.List.of("some-mod"), PathWeaverConfig.get().trustedMods,
            "the editor's list is still the published one");
    }
}
