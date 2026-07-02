package dev.pathweaver.gate;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.pathweaver.PathWeaver;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.world.level.pathfinder.AmphibiousNodeEvaluator;
import net.minecraft.world.level.pathfinder.FlyNodeEvaluator;
import net.minecraft.world.level.pathfinder.SwimNodeEvaluator;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * At startup, detects whether any OTHER mod mixes into one of our allowlisted vanilla evaluator
 * classes (or {@code PathFinder}). Such a mixin keeps the class identity intact, so the exact-class
 * allowlist cannot see it — this scanner is what closes that hole. Any hit is added to
 * {@link SafetyGate#deniedBySafety}, forcing that evaluator family back to synchronous pathing.
 *
 * Everything here is defensive: a scan failure must never crash startup, only reduce coverage.
 */
public final class ForeignMixinScanner {

    private static final Map<String, Class<?>> ALLOWLISTED_BY_NAME = Map.of(
        WalkNodeEvaluator.class.getName(), WalkNodeEvaluator.class,
        SwimNodeEvaluator.class.getName(), SwimNodeEvaluator.class,
        AmphibiousNodeEvaluator.class.getName(), AmphibiousNodeEvaluator.class,
        FlyNodeEvaluator.class.getName(), FlyNodeEvaluator.class
    );
    private static final String PATHFINDER = "net.minecraft.world.level.pathfinder.PathFinder";

    private ForeignMixinScanner() {}

    /** Pure, testable: map fully-qualified mixin target names to the allowlisted classes they hit. */
    public static Set<Class<?>> targetsTouchingAllowlist(Collection<String> targetClassNames) {
        Set<Class<?>> hits = new HashSet<>();
        for (String t : targetClassNames) {
            Class<?> c = ALLOWLISTED_BY_NAME.get(t);
            if (c != null) hits.add(c);
        }
        return hits;
    }

    /**
     * Read the target class names declared by {@code @Mixin} on a mixin class, from its bytecode.
     * Handles both {@code @Mixin(SomeClass.class)} (value = Type[]) and
     * {@code @Mixin(targets = "a.b.C")} (targets = String[]). No class loading/initialization.
     */
    public static List<String> readMixinAnnotationTargets(byte[] classBytes) {
        List<String> out = new ArrayList<>();
        ClassReader cr = new ClassReader(classBytes);
        cr.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                if (!"Lorg/spongepowered/asm/mixin/Mixin;".equals(desc)) return null;
                return new AnnotationVisitor(Opcodes.ASM9) {
                    @Override public AnnotationVisitor visitArray(String name) {
                        // name is "value" (Type[]) or "targets" (String[])
                        return new AnnotationVisitor(Opcodes.ASM9) {
                            @Override public void visit(String n, Object value) {
                                if (value instanceof Type t) out.add(t.getClassName());
                                else if (value instanceof String s) out.add(s);
                            }
                        };
                    }
                };
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return out;
    }

    /** Environment scan: walk every non-PathWeaver mod's mixin configs, deny any allowlist hits. */
    public static void scanAndPopulate() {
        boolean pathFinderTouched = false;
        for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
            String id = mod.getMetadata().getId();
            if (PathWeaver.MOD_ID.equals(id)) continue;
            try {
                Collection<String> targets = collectMixinTargets(mod);
                for (Class<?> c : targetsTouchingAllowlist(targets)) {
                    SafetyGate.deniedBySafety.add(c);
                    PathWeaver.LOG.warn("Mod '{}' mixes into {}; forcing that evaluator family to sync pathing.",
                        id, c.getSimpleName());
                }
                if (targets.contains(PATHFINDER)) {
                    pathFinderTouched = true;
                    PathWeaver.LOG.warn("Mod '{}' mixes into PathFinder; async path fidelity may be affected.", id);
                }
            } catch (Throwable t) {
                PathWeaver.LOG.debug("Foreign-mixin scan skipped mod '{}': {}", id, t.toString());
            }
        }
        if (pathFinderTouched) {
            PathWeaver.LOG.warn("A mod targets PathFinder — verify path fidelity in-game before trusting async results.");
        }
        PathWeaver.LOG.info("Foreign-mixin scan complete; {} evaluator family(ies) forced sync.",
            SafetyGate.deniedBySafety.size());
    }

    /** Gather every @Mixin target declared across a mod's mixin configs. */
    private static Collection<String> collectMixinTargets(ModContainer mod) {
        Set<String> targets = new HashSet<>();
        for (Path root : mod.getRootPaths()) {
            for (String cfgName : mixinConfigNames(root)) {
                Path cfg = root.resolve(cfgName);
                if (!Files.exists(cfg)) continue;
                JsonObject o;
                try (var in = Files.newInputStream(cfg)) {
                    o = JsonParser.parseReader(new InputStreamReader(in)).getAsJsonObject();
                } catch (Exception e) { continue; }
                String pkg = o.has("package") ? o.get("package").getAsString() : "";
                for (String key : List.of("mixins", "client", "server")) {
                    if (!o.has(key) || !o.get(key).isJsonArray()) continue;
                    JsonArray arr = o.getAsJsonArray(key);
                    for (var el : arr) {
                        String simple = el.getAsString();
                        String fqName = pkg.isEmpty() ? simple : pkg + "." + simple;
                        byte[] bytes = readClassBytes(root, fqName);
                        if (bytes != null) targets.addAll(readMixinAnnotationTargets(bytes));
                    }
                }
            }
        }
        return targets;
    }

    private static List<String> mixinConfigNames(Path root) {
        List<String> out = new ArrayList<>();
        try (Stream<Path> s = Files.list(root)) {
            s.filter(p -> p.getFileName().toString().endsWith("mixins.json"))
             .forEach(p -> out.add(p.getFileName().toString()));
        } catch (Exception ignored) {}
        return out;
    }

    private static byte[] readClassBytes(Path root, String fqName) {
        Path classFile = root.resolve(fqName.replace('.', '/') + ".class");
        try {
            return Files.exists(classFile) ? Files.readAllBytes(classFile) : null;
        } catch (Exception e) { return null; }
    }
}
