package dev.pathweaver.mixin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.pathweaver.gate.SafetyGate;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Every live-mob read an admitted evaluator makes from inside a search must be redirected, and the
 * covered set is derived from the shipped mixin configuration rather than restated here.
 *
 * <p>This rule has now been got wrong twice, and each rewrite tightened it:
 *
 * <ul>
 *   <li>0.5.0 had no such test. {@code AmphibiousNodeEvaluator} overrides {@code getNeighbors} and
 *       makes its own {@code maxUpStep()} call, so four mob families raced while {@code require = 1}
 *       passed on {@code WalkNodeEvaluator} and proved nothing about the subclass.</li>
 *   <li>0.5.1 added a version keyed on <em>class names</em> against a hand-written literal. It would
 *       have stayed green if the new mixin were deleted from {@code pathweaver.mixins.json}, and it
 *       could not catch a second read added to a class already in the list — which is exactly what
 *       {@code getMaxFallDistance()} is: declared by {@code WalkNodeEvaluator} itself, reached from
 *       the A* loop via {@code findAcceptedNode → tryFindFirstGroundNodeBelow}, racing the mob's
 *       cached MAX_HEALTH for every admitted family whenever that mob has a target.</li>
 * </ul>
 *
 * <p>Coverage is therefore keyed on the exact <em>(declaring class, declaring method, called
 * method)</em> triple, and the covered side is read out of the {@code @Redirect} annotations of the
 * mixins actually listed in {@code pathweaver.mixins.json}. Deleting a mixin from that config now
 * fails, and so does a new read in an already-covered class.
 */
class LiveMobReadCoverageContractTest {

    /**
     * Calls on the live mob that must not run on a worker.
     *
     * <p>Hand-maintained, and that is this test's remaining weakness. Each bottoms out in either
     * {@code AttributeInstance.getValue()} — {@code if (dirty) { cachedValue = calculateValue();
     * dirty = false; }} over plain non-volatile fields — or a shared {@code RandomSource}. The
     * structural version walks the call graph for those two sinks instead of naming their callers;
     * that is on the 0.6 roadmap, and until it exists this list is the thing most likely to be
     * incomplete.
     */
    private static final Set<String> CONFINED_MOB_READS =
        Set.of("maxUpStep", "getRandom", "getMaxFallDistance");

    /**
     * A call site keyed on everything that decides whether a redirect actually applies.
     *
     * <p>{@code call} is the full {@code owner.name descriptor} of the invoked method, not the bare
     * name. Keying on the name alone let a redirect whose {@code at.target} named the wrong owner
     * ({@code LivingEntity} instead of {@code Mob}) or the wrong return descriptor be counted as
     * covering a site it could never bind to — and paired with {@code require = 0} that is
     * behaviourally identical to deleting the mixin, which this contract exists to catch.
     */
    private record Site(String owner, String method, String call) {
        @Override public String toString() { return owner + "#" + method + " -> " + call; }
    }

    private static final String MIXIN_DESC = "Lorg/spongepowered/asm/mixin/Mixin;";
    private static final String REDIRECT_DESC =
        "Lorg/spongepowered/asm/mixin/injection/Redirect;";

    private static Set<Site> sorted() {
        return new TreeSet<>(Comparator.comparing(Site::toString));
    }

    @Test
    void everyConfinedReadAnAdmittedEvaluatorDeclaresIsRedirected() throws Exception {
        Set<Site> declared = sorted();
        for (Class<?> evaluator : SafetyGate.allowlisted()) {
            for (Class<?> level = evaluator;
                    level != null && level.getName().startsWith("net.minecraft");
                    level = level.getSuperclass()) {
                declared.addAll(confinedReadsDeclaredBy(level));
            }
        }
        assertFalse(declared.isEmpty(),
            "found no confined reads at all — this test stopped looking rather than the hazard "
                + "stopping existing");

        Set<Site> redirected = sorted();
        redirected.addAll(redirectedSitesFromShippedMixinConfig());

        assertEquals(declared, redirected,
            "every confined live-mob read an admitted evaluator DECLARES must be redirected by a "
                + "mixin listed in pathweaver.mixins.json. A @Redirect transforms only its target "
                + "class, so an override in a subclass is a different method sharing a name.");
    }

    @Test
    void theCoveredSetComesFromTheShippedConfigNotFromThisTest() throws Exception {
        // Non-vacuity: a green result must not be able to mean "parsed nothing, compared two empty
        // sets". Prove the derivation reaches the real config and resolves real annotations.
        Set<String> mixinClasses = shippedMixinClassNames();
        assertTrue(mixinClasses.contains("WalkNodeEvaluatorMixin"),
            "the shipped config should list the walk mixin: " + mixinClasses);
        assertTrue(mixinClasses.contains("AmphibiousNodeEvaluatorMixin"),
            "the shipped config should list the amphibious mixin: " + mixinClasses);
        assertFalse(redirectedSitesFromShippedMixinConfig().isEmpty(),
            "no @Redirect sites were resolved from the shipped mixins");
    }

    /**
     * Every confined-read redirect must be required to bind.
     *
     * <p>Coverage is derived from annotations, so an injector Mixin is permitted to skip counts as
     * protection while providing none. {@code require = 0} plus a target string with the wrong owner
     * or descriptor is behaviourally identical to deleting the mixin — the exact failure the rest of
     * this class exists to prevent, arriving through the one door it did not watch.
     */
    @Test
    void everyConfinedReadRedirectMustBeRequiredToBind() throws Exception {
        redirectedSitesFromShippedMixinConfig();
        assertTrue(WEAK_REQUIRE.isEmpty(),
            "these confined-read redirects can be silently skipped by Mixin, so they are not "
                + "coverage: " + WEAK_REQUIRE);
    }

    @Test
    void theAmphibiousEvaluatorDeclaresItsOwnStepHeightRead() throws IOException {
        assertTrue(confinedReadsDeclaredBy(
                net.minecraft.world.level.pathfinder.AmphibiousNodeEvaluator.class).stream()
                .anyMatch(s -> bareName(s.call()).equals("maxUpStep")),
            "AmphibiousNodeEvaluator is expected to declare its own maxUpStep() call — the fact that "
                + "shipped broken in 0.5.0");
    }

    /**
     * The third entry in {@link #CONFINED_MOB_READS} needs a pin like the other two.
     *
     * <p>Without one, deleting {@code "getRandom"} from the literal AND deleting
     * {@code FlyNodeEvaluatorMixin}'s redirect shrinks both sides of the comparison symmetrically and
     * every other test in this class stays green — the hazard is reintroduced and nothing objects.
     * Each name in that set must be anchored to the real bytecode that makes it a hazard.
     */
    @Test
    void flyDeclaresTheSharedRandomSourceReadFromItsStartNodeChoice() throws IOException {
        assertTrue(confinedReadsDeclaredBy(
                net.minecraft.world.level.pathfinder.FlyNodeEvaluator.class).stream()
                .anyMatch(s -> bareName(s.call()).equals("getRandom")),
            "FlyNodeEvaluator is expected to declare getRandom() — it draws its start candidate from "
                + "the mob's shared RandomSource, which is the reason flying mobs pathed "
                + "synchronously before it was confined");
    }

    @Test
    void walkDeclaresTheMaxFallDistanceReadReachedFromTheSearch() throws IOException {
        assertTrue(confinedReadsDeclaredBy(
                net.minecraft.world.level.pathfinder.WalkNodeEvaluator.class).stream()
                .anyMatch(s -> bareName(s.call()).equals("getMaxFallDistance")),
            "WalkNodeEvaluator is expected to declare getMaxFallDistance() — it shipped unredirected "
                + "in both 0.5.0 and 0.5.1 and affects every admitted family");
    }

    /** {@code (class, method, call)} triples for confined reads in this class's own bytecode. */
    private static Set<Site> confinedReadsDeclaredBy(Class<?> type) throws IOException {
        Set<Site> found = new LinkedHashSet<>();
        String owner = type.getName();
        new ClassReader(classBytes(type)).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                String declaring = name + descriptor;
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String callOwner, String called,
                                                String calledDescriptor, boolean isInterface) {
                        if (callOwner.equals("net/minecraft/world/entity/Mob")
                                && CONFINED_MOB_READS.contains(called)) {
                            found.add(new Site(owner, declaring,
                                callOwner + "." + called + calledDescriptor));
                        }
                    }
                };
            }
        }, ClassReader.SKIP_FRAMES);
        return found;
    }

    /**
     * Read the shipped mixin list and expand each class's {@code @Redirect}s, via ASM.
     *
     * <p>Not reflection: Mixin's annotations are {@code RetentionPolicy.CLASS}, so they do not exist
     * at runtime and {@code getAnnotation} silently returns null for every one of them — which would
     * make this whole contract compare a populated set against an empty one and pass only when the
     * production side was equally empty.
     */
    /** Redirects that Mixin is allowed to skip silently. Populated by the scan below. */
    private static final Set<String> WEAK_REQUIRE = new LinkedHashSet<>();

    private static Set<Site> redirectedSitesFromShippedMixinConfig() throws Exception {
        Set<Site> sites = new LinkedHashSet<>();
        Set<String> weakRequire = WEAK_REQUIRE;
        for (String simple : shippedMixinClassNames()) {
            Class<?> mixin = Class.forName("dev.pathweaver.mixin." + simple);
            String[] targetedClass = new String[1];
            new ClassReader(classBytes(mixin)).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                    if (!desc.equals(MIXIN_DESC)) return null;
                    return new AnnotationVisitor(Opcodes.ASM9) {
                        @Override public AnnotationVisitor visitArray(String key) {
                            if (!key.equals("value")) return null;
                            return new AnnotationVisitor(Opcodes.ASM9) {
                                @Override public void visit(String ignored, Object value) {
                                    targetedClass[0] =
                                        ((org.objectweb.asm.Type) value).getClassName();
                                }
                            };
                        }
                    };
                }

                @Override public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                           String signature, String[] exceptions) {
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override public AnnotationVisitor visitAnnotation(String desc, boolean vis) {
                            if (!desc.equals(REDIRECT_DESC)) return null;
                            List<String> methods = new ArrayList<>();
                            String[] call = new String[1];
                            Integer[] require = new Integer[1];
                            return new AnnotationVisitor(Opcodes.ASM9) {
                                @Override public void visit(String key, Object v) {
                                    if (key.equals("require")) require[0] = (Integer) v;
                                }
                                @Override public AnnotationVisitor visitArray(String key) {
                                    if (!key.equals("method")) return null;
                                    return new AnnotationVisitor(Opcodes.ASM9) {
                                        @Override public void visit(String ig, Object v) {
                                            methods.add((String) v);
                                        }
                                    };
                                }
                                @Override public AnnotationVisitor visitAnnotation(String key, String d) {
                                    if (!key.equals("at")) return null;
                                    return new AnnotationVisitor(Opcodes.ASM9) {
                                        @Override public void visit(String k, Object v) {
                                            if (k.equals("target")) call[0] = signatureOf((String) v);
                                        }
                                    };
                                }
                                @Override public void visitEnd() {
                                    if (call[0] == null || !CONFINED_MOB_READS.contains(bareName(call[0]))) {
                                        return;
                                    }
                                    // A redirect that does not have to bind is not coverage. Mixin
                                    // silently skips an unmatched injector at require = 0, so without
                                    // this a typo'd target reads as protection.
                                    if (require[0] != null && require[0] < 1) {
                                        weakRequire.add(targetedClass[0] + " -> " + call[0]);
                                    }
                                    for (String m : methods) {
                                        sites.add(new Site(targetedClass[0], m, call[0]));
                                    }
                                }
                            };
                        }
                    };
                }
            }, ClassReader.SKIP_FRAMES);
        }
        return sites;
    }

    /**
     * {@code Lnet/minecraft/world/entity/Mob;maxUpStep()F} ->
     * {@code net/minecraft/world/entity/Mob.maxUpStep()F}.
     *
     * <p>Owner and descriptor are kept. They are exactly what decides whether the injector binds.
     */
    private static String signatureOf(String atTarget) {
        int semi = atTarget.indexOf(';');
        int paren = atTarget.indexOf('(', semi + 1);
        if (semi < 0 || paren < 0 || !atTarget.startsWith("L")) return null;
        String owner = atTarget.substring(1, semi);
        return owner + "." + atTarget.substring(semi + 1);
    }

    /** {@code net/minecraft/world/entity/Mob.maxUpStep()F} -> {@code maxUpStep}. */
    private static String bareName(String signature) {
        int dot = signature.lastIndexOf('.');
        int paren = signature.indexOf('(', dot + 1);
        if (dot < 0 || paren < 0) return signature;
        return signature.substring(dot + 1, paren);
    }

    private static Set<String> shippedMixinClassNames() throws IOException {
        try (InputStream in = LiveMobReadCoverageContractTest.class
                .getResourceAsStream("/pathweaver.mixins.json")) {
            if (in == null) throw new IOException("pathweaver.mixins.json is not on the test classpath");
            JsonObject root = JsonParser.parseString(
                new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
            Set<String> names = new LinkedHashSet<>();
            root.getAsJsonArray("mixins").forEach(e -> names.add(e.getAsString()));
            return names;
        }
    }

    private static byte[] classBytes(Class<?> type) throws IOException {
        String resource = "/" + type.getName().replace('.', '/') + ".class";
        try (InputStream in = type.getResourceAsStream(resource)) {
            if (in == null) throw new IOException("missing class resource " + resource);
            return in.readAllBytes();
        }
    }
}
