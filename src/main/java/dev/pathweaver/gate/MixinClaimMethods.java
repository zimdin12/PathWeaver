package dev.pathweaver.gate;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Which methods of its target a foreign mixin actually injects into.
 *
 * <p>The gate has always asked "did any mod touch this class?", and that is the wrong question.
 * Measured on a real 317-jar pack: 21 mods claim a watched pathfinding target and any single one
 * denies every evaluator family, so {@code compatibilityTier=AUDITED} leaves <em>0 of 187</em> mob
 * types eligible — the mod's own safety mechanism is unusable and the shipped default is
 * {@code UNSAFE}. Ten of those mods touch only {@code BlockBehaviour$BlockStateBase}, and most are
 * not pathfinding mods at all: FerriteCore changes state storage, ModernFix caches, Balm injects
 * {@code getDestroyProgress}.
 *
 * <p>The narrower question is whether the injected method is one a search can reach. It is not a
 * softer question — {@code expandability} and {@code vehicleupgrade} inject
 * {@code getCollisionShape} and {@code terrain_slabs} injects {@code getShape}, all of which
 * {@code WalkNodeEvaluator.getFloorLevel} really does call, and those must keep denying.
 *
 * <h2>Which way it fails</h2>
 *
 * <p>Closed, at every step. This class exists to let a claim be <em>cleared</em>, so an incomplete
 * answer must never read as "harmless":
 *
 * <ul>
 *   <li>Bytes that cannot be read, an annotation shape that is not understood, or an injector with
 *       no resolvable target method all yield {@link Optional#empty()}, and the caller denies.
 *   <li>{@code @Overwrite} and a bare {@code @Mixin} with no injectors both yield empty: the first
 *       replaces a method wholesale and the second may still add fields or interfaces.
 *   <li>Wildcards and regular-expression selectors yield empty. Mixin accepts {@code method = "*"}
 *       and {@code method = "/regex/"}, and resolving those properly means resolving them against
 *       the target class, which is exactly the work this is trying to avoid doing wrong.
 * </ul>
 */
final class MixinClaimMethods {

    private static final String MIXIN_PACKAGE = "Lorg/spongepowered/asm/mixin/";

    /**
     * Injector annotations that name their target through a {@code method} element.
     *
     * <p>{@code Accessor} and {@code Invoker} are deliberately absent: they name a FIELD or generate
     * a call, do not run foreign code inside the target method, and are handled by the caller as a
     * separate, weaker claim.
     */
    private static final Set<String> METHOD_TARGETED_INJECTORS = Set.of(
        "Lorg/spongepowered/asm/mixin/injection/Inject;",
        "Lorg/spongepowered/asm/mixin/injection/Redirect;",
        "Lorg/spongepowered/asm/mixin/injection/ModifyArg;",
        "Lorg/spongepowered/asm/mixin/injection/ModifyArgs;",
        "Lorg/spongepowered/asm/mixin/injection/ModifyConstant;",
        "Lorg/spongepowered/asm/mixin/injection/ModifyVariable;",
        "Lcom/llamalad7/mixinextras/injector/ModifyExpressionValue;",
        "Lcom/llamalad7/mixinextras/injector/ModifyReturnValue;",
        "Lcom/llamalad7/mixinextras/injector/ModifyReceiver;",
        "Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;",
        "Lcom/llamalad7/mixinextras/injector/WrapWithCondition;",
        "Lcom/llamalad7/mixinextras/sugar/Local;");

    private MixinClaimMethods() {}

    /**
     * Target method names this mixin class injects into, or empty when that cannot be established.
     *
     * @param mixinClassName fully-qualified name of the foreign mixin class
     */
    static Optional<Set<String>> injectedMethodsOf(String mixinClassName) {
        byte[] bytes;
        try {
            bytes = readBytes(mixinClassName);
        } catch (IOException | RuntimeException unreadable) {
            return Optional.empty();
        }
        if (bytes == null) return Optional.empty();

        Set<String> methods = new LinkedHashSet<>();
        boolean[] sawInjector = {false};
        boolean[] unresolvable = {false};
        try {
            new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                    // A class-level @Overwrite-style or plugin annotation is not something this
                    // class models; only a target-method claim can be narrowed.
                    return null;
                }

                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                 String signature, String[] exceptions) {
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                            if (desc.equals("Lorg/spongepowered/asm/mixin/Overwrite;")) {
                                // Replaces the target method wholesale. Its name is this method's
                                // name, but an overwrite is not an injection into a known method --
                                // treat as unresolvable so the caller keeps denying.
                                unresolvable[0] = true;
                                return null;
                            }
                            if (!METHOD_TARGETED_INJECTORS.contains(desc)) {
                                if (desc.startsWith(MIXIN_PACKAGE)
                                        && !desc.equals("Lorg/spongepowered/asm/mixin/gen/Accessor;")
                                        && !desc.equals("Lorg/spongepowered/asm/mixin/gen/Invoker;")
                                        && !desc.equals("Lorg/spongepowered/asm/mixin/Shadow;")
                                        && !desc.equals("Lorg/spongepowered/asm/mixin/Unique;")
                                        && !desc.equals("Lorg/spongepowered/asm/mixin/Final;")) {
                                    // A Mixin annotation this class does not model. Refuse to guess.
                                    unresolvable[0] = true;
                                }
                                return null;
                            }
                            sawInjector[0] = true;
                            return new AnnotationVisitor(Opcodes.ASM9) {
                                @Override public AnnotationVisitor visitArray(String key) {
                                    if (!"method".equals(key)) return null;
                                    return new AnnotationVisitor(Opcodes.ASM9) {
                                        @Override public void visit(String ignored, Object value) {
                                            String selector = String.valueOf(value);
                                            String bare = bareMethodName(selector);
                                            if (bare == null) unresolvable[0] = true;
                                            else methods.add(bare);
                                        }
                                    };
                                }

                                @Override public void visit(String key, Object value) {
                                    // Mixin allows a single string rather than an array.
                                    if (!"method".equals(key)) return;
                                    String bare = bareMethodName(String.valueOf(value));
                                    if (bare == null) unresolvable[0] = true;
                                    else methods.add(bare);
                                }
                            };
                        }
                    };
                }
            }, ClassReader.SKIP_CODE | ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
        } catch (RuntimeException malformed) {
            return Optional.empty();
        }

        if (unresolvable[0]) return Optional.empty();
        if (!sawInjector[0]) return Optional.empty();
        if (methods.isEmpty()) return Optional.empty();
        return Optional.of(Set.copyOf(methods));
    }

    /**
     * {@code "getCollisionShape(Lnet/...;)V"} or {@code "Lnet/x;getCollisionShape()V"} -> the name.
     *
     * <p>Returns null for anything that is not a plain name — a wildcard, a regular expression, or a
     * selector shape not recognised — so the caller fails closed rather than matching the wrong
     * method.
     */
    static String bareMethodName(String selector) {
        if (selector == null || selector.isBlank()) return null;
        String s = selector.trim();
        if (s.startsWith("/")) return null;                       // regex selector
        if (s.indexOf('*') >= 0 || s.indexOf('{') >= 0) return null;  // wildcard / dynamic selector
        int owner = s.lastIndexOf(';', s.indexOf('(') < 0 ? s.length() - 1 : s.indexOf('('));
        if (owner >= 0) s = s.substring(owner + 1);
        int paren = s.indexOf('(');
        if (paren >= 0) s = s.substring(0, paren);
        int colon = s.indexOf(':');
        if (colon >= 0) s = s.substring(0, colon);
        s = s.trim();
        if (s.isEmpty()) return null;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = Character.isJavaIdentifierPart(c) || c == '<' || c == '>';
            if (!ok) return null;
        }
        return s;
    }

    private static byte[] readBytes(String className) throws IOException {
        String resource = "/" + className.replace('.', '/') + ".class";
        try (InputStream in = MixinClaimMethods.class.getResourceAsStream(resource)) {
            if (in != null) return in.readAllBytes();
        }
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        if (loader == null) return null;
        try (InputStream in = loader.getResourceAsStream(resource.substring(1))) {
            return in == null ? null : in.readAllBytes();
        }
    }
}
