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
 * <p>Scope, stated because the file is about scope: this covers direct field writes from production
 * classes. It does not cover reflection, and it does not cover writes from tests, which do exist and
 * are the reason {@code get()} still returns a mutable object.
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
}
