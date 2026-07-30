package dev.pathweaver.gate;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Exact audit for Farmer's Delight's stove, the one dynamic land path-type provider worth clearing.
 *
 * <p>A {@code DynamicPathTypeProvider} receives a {@code BlockGetter} and a {@code BlockPos}, so
 * {@link CertifiedLandProviders} cannot precompute it the way it does the static form and any such
 * registration denies Walk for the rest of the process. Farmer's Delight is popular enough that this
 * alone switched PathWeaver off on a large number of packs.
 *
 * <p>Its provider does not actually read the world. It forwards to a method whose entire body is:
 *
 * <pre>
 *   getBlockPathType(BlockState, BlockGetter, BlockPos, Mob)
 *     0: aload_1                 // the BlockState, and nothing else
 *     1: getstatic LIT
 *     4: getValue -> booleanValue
 *    13: ifeq 22 -> PathType.FIRE : null
 * </pre>
 *
 * <p>The world and position are received and never loaded, so the answer is a function of the
 * {@code LIT} property alone and is precomputable exactly like a static provider.
 *
 * <p>Why this is a per-artifact audit and not a general rule: proving an arbitrary dynamic provider
 * ignores its world argument is a transitive escape analysis through mod code with virtual dispatch,
 * not a signature check. Two cheap substitutes are unsound and are deliberately not used. Invoking
 * the provider with a null world would let a provider that branches on {@code world == null} answer
 * differently under a real world. Checking only that the implementation method never loads those
 * locals fails on this very mod, because it forwards them to a method that ignores them.
 *
 * <p>So the audit follows the one call edge by hand and pins every step. It fails closed on anything
 * unexpected, including a different artifact, an extra {@code invokedynamic}, an extra call in the
 * forwarding lambda, a subclass override anywhere in the jar, or a nested jar that could contribute
 * one. The runtime check additionally resolves the override against the concrete block being
 * registered, so an addon subclassing the stove cannot inherit this finding.
 */
final class FarmersDelightStoveCompatibility {
    static final String MOD_ID = "farmersdelight";
    static final String MOD_VERSION = "26.1-3.6.7+refabricated";

    static final String HOST = "vectorwing.farmersdelight.common.block.AbstractStoveBlock";
    private static final String HOST_INTERNAL =
        "vectorwing/farmersdelight/common/block/AbstractStoveBlock";
    private static final String PROVIDER_INTERFACE =
        "Lnet/fabricmc/fabric/api/registry/LandPathTypeRegistry$DynamicPathTypeProvider;";

    /** The forwarding lambda the single invokedynamic must target. */
    private static final String LAMBDA_NAME = "lambda$new$0";
    private static final String LAMBDA_DESC =
        "(Lnet/minecraft/world/level/block/state/BlockState;"
            + "Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Z)"
            + "Lnet/minecraft/world/level/pathfinder/PathType;";

    /** The method that actually decides, and must ignore the world and the position. */
    static final String DECIDER_NAME = "getBlockPathType";
    private static final String DECIDER_DESC =
        "(Lnet/minecraft/world/level/block/state/BlockState;"
            + "Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;"
            + "Lnet/minecraft/world/entity/Mob;)Lnet/minecraft/world/level/pathfinder/PathType;";

    /**
     * Local slots of the world and the position in {@link #DECIDER_NAME}.
     *
     * <p>It is an instance method, so slot 0 is {@code this}, 1 is the state, 2 the {@code BlockGetter}
     * and 3 the {@code BlockPos}. Any reference to 2 or 3 fails the audit, including a store, which is
     * conservative: reusing the slot as scratch would be harmless but is not worth distinguishing.
     */
    private static final int WORLD_SLOT = 2;
    private static final int POS_SLOT = 3;

    private static final String MODULE_SHA =
        "25adee6361b37f1e559373bf6aedc90fa62b2da8ab084e3dee53f037ffcac636";
    private static final String HOST_CLASS_SHA =
        "e0337efef54e6c2a27aad6746298221da4cd83141a270832502a461420117ec4";

    /** Calls the forwarding lambda is allowed to make. Anything else means it does more than forward. */
    private static final Set<String> LAMBDA_ALLOWED_CALLS = Set.of(
        "net/minecraft/world/level/block/state/BlockState.getBlock",
        HOST_INTERNAL + "." + DECIDER_NAME);

    private FarmersDelightStoveCompatibility() {}

    /** Audited once per process; the result is a property of the artifact, not of any registration. */
    private static volatile Boolean audited;

    /**
     * True when this exact Farmer's Delight build's stove provider is proven world-independent.
     *
     * <p>Absent or different build, or any failed proof, returns false and the caller must deny.
     */
    static boolean providerIsWorldIndependent() {
        Boolean cached = audited;
        if (cached != null) return cached;
        synchronized (FarmersDelightStoveCompatibility.class) {
            if (audited != null) return audited;
            List<String> diagnostics = new ArrayList<>();
            boolean verified = run(diagnostics);
            if (!verified) {
                for (String diagnostic : diagnostics) {
                    dev.pathweaver.PathWeaver.LOG.warn(
                        "Farmer's Delight stove audit failed (fail-closed): {}", diagnostic);
                }
            }
            audited = verified;
            return verified;
        }
    }

    private static boolean run(List<String> diagnostics) {
        try {
            Optional<ModContainer> container =
                FabricLoader.getInstance().getModContainer(MOD_ID);
            if (container.isEmpty()) {
                diagnostics.add("mod not present");
                return false;
            }
            ModContainer module = container.get();
            String version = module.getMetadata().getVersion().getFriendlyString();
            if (!MOD_VERSION.equals(version)) {
                diagnostics.add("unsupported version " + version);
                return false;
            }
            byte[] jar = AuditedMixinCompatibility.readModuleArtifact(module);
            AuditedMixinCompatibility.checkHash("Farmer's Delight jar", jar, MODULE_SHA, diagnostics);
            byte[] host = AuditedMixinCompatibility.readModResource(
                module, AuditedMixinCompatibility.classResource(HOST));
            AuditedMixinCompatibility.checkHash(
                "Farmer's Delight AbstractStoveBlock", host, HOST_CLASS_SHA, diagnostics);
            verifySingleForwardingProvider(host, diagnostics);
            verifyDeciderIgnoresWorldAndPosition(host, diagnostics);
            verifyNoOtherDeciderInJar(jar, diagnostics);
            return diagnostics.isEmpty();
        } catch (Throwable failure) {
            diagnostics.add("audit aborted: " + failure);
            return false;
        }
    }

    /**
     * Require exactly one {@code invokedynamic} producing the provider interface, targeting the
     * pinned forwarding lambda, and require that lambda to do nothing but forward.
     *
     * <p>Resolving the implementation method from the bootstrap arguments is what makes the runtime
     * identity check sound: a lambda's class has no readable bytecode, so the provider instance is
     * matched by its host class, and that is only meaningful once this class is known to contain a
     * single provider lambda.
     */
    static void verifySingleForwardingProvider(byte[] hostBytes, List<String> diagnostics) {
        ClassNode node = AuditedMixinCompatibility.classNode(hostBytes);
        List<Handle> targets = new ArrayList<>();
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof InvokeDynamicInsnNode indy
                        && indy.desc.endsWith(")" + PROVIDER_INTERFACE)) {
                    for (Object argument : indy.bsmArgs) {
                        if (argument instanceof Handle handle) targets.add(handle);
                    }
                }
            }
        }
        if (targets.size() != 1) {
            diagnostics.add("expected exactly one provider lambda, found " + targets.size());
            return;
        }
        Handle target = targets.get(0);
        if (!HOST_INTERNAL.equals(target.getOwner())
                || !LAMBDA_NAME.equals(target.getName())
                || !LAMBDA_DESC.equals(target.getDesc())) {
            diagnostics.add("provider lambda is not the audited method: "
                + target.getOwner() + "." + target.getName() + target.getDesc());
            return;
        }
        MethodNode lambda = null;
        for (MethodNode method : node.methods) {
            if (LAMBDA_NAME.equals(method.name) && LAMBDA_DESC.equals(method.desc)) lambda = method;
        }
        if (lambda == null) {
            diagnostics.add("provider lambda body missing");
            return;
        }
        for (AbstractInsnNode insn : lambda.instructions) {
            if (insn instanceof MethodInsnNode call
                    && !LAMBDA_ALLOWED_CALLS.contains(call.owner + "." + call.name)) {
                diagnostics.add("provider lambda calls beyond forwarding: "
                    + call.owner + "." + call.name + call.desc);
            }
            if (insn instanceof InvokeDynamicInsnNode) {
                diagnostics.add("provider lambda contains a nested invokedynamic");
            }
        }
    }

    /** The proof that matters: the decider never touches the world or the position it is handed. */
    static void verifyDeciderIgnoresWorldAndPosition(byte[] hostBytes, List<String> diagnostics) {
        ClassNode node = AuditedMixinCompatibility.classNode(hostBytes);
        MethodNode decider = null;
        for (MethodNode method : node.methods) {
            if (DECIDER_NAME.equals(method.name) && DECIDER_DESC.equals(method.desc)) decider = method;
        }
        if (decider == null) {
            diagnostics.add("decider " + DECIDER_NAME + DECIDER_DESC + " missing");
            return;
        }
        for (AbstractInsnNode insn : decider.instructions) {
            if (insn instanceof VarInsnNode local
                    && (local.var == WORLD_SLOT || local.var == POS_SLOT)) {
                diagnostics.add("decider references local slot " + local.var
                    + " (the world or position), opcode " + local.getOpcode());
            }
            if (insn.getOpcode() == Opcodes.IINC) {
                diagnostics.add("decider mutates a local slot; the parameter analysis is not valid");
            }
        }
    }

    /**
     * Close the virtual-dispatch hole: the decider is invoked virtually, so a subclass override
     * would be dispatched to instead. Require the jar to declare it exactly once, and to carry no
     * nested jar that could contribute another implementation.
     */
    static void verifyNoOtherDeciderInJar(byte[] jarBytes, List<String> diagnostics) {
        Set<String> declaring = new HashSet<>();
        List<String> nested = new ArrayList<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(jarBytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (name.startsWith("META-INF/jars/") && name.endsWith(".jar")) {
                    nested.add(name);
                    continue;
                }
                if (!name.endsWith(".class")) continue;
                byte[] bytes = zip.readAllBytes();
                ClassNode node;
                try {
                    node = AuditedMixinCompatibility.classNode(bytes);
                } catch (Throwable unreadable) {
                    diagnostics.add("unreadable class in jar: " + name);
                    return;
                }
                for (MethodNode method : node.methods) {
                    if (DECIDER_NAME.equals(method.name) && DECIDER_DESC.equals(method.desc)) {
                        declaring.add(node.name);
                    }
                }
            }
        } catch (Throwable failure) {
            diagnostics.add("could not scan jar for overrides: " + failure);
            return;
        }
        if (!nested.isEmpty()) {
            diagnostics.add("jar carries nested jars that were not audited: " + nested);
        }
        if (!declaring.equals(Set.of(HOST_INTERNAL))) {
            diagnostics.add("decider is declared by " + declaring
                + "; exactly one implementation is required");
        }
    }

    /**
     * Runtime half of the override proof, for the concrete block being registered.
     *
     * <p>The jar scan cannot see a subclass from another mod. An addon stove that overrides the
     * decider to read the world would be dispatched to instead, so require that nothing between the
     * registered block's class and the audited host declares it.
     */
    static boolean deciderNotOverriddenFor(Class<?> blockClass) {
        for (Class<?> type = blockClass; type != null; type = type.getSuperclass()) {
            if (HOST.equals(type.getName())) return true;
            try {
                type.getDeclaredMethod(DECIDER_NAME,
                    net.minecraft.world.level.block.state.BlockState.class,
                    net.minecraft.world.level.BlockGetter.class,
                    net.minecraft.core.BlockPos.class,
                    net.minecraft.world.entity.Mob.class);
                return false;   // an override below the audited host: not covered by this finding
            } catch (NoSuchMethodException expected) {
                // this class does not override it; keep walking up
            } catch (Throwable unexpected) {
                return false;
            }
        }
        return false;           // never reached the audited host
    }

    /**
     * True when this provider instance really is a lambda of the audited host class.
     *
     * <p>Matching the class name prefix is not identity — another jar could define a class of the
     * same name, and a transformed host could supply a same-prefix implementation. A lambda's own
     * class has no readable bytecode, but it is a nestmate of the class that declares it, so its nest
     * host is compared by reference against the class the audited name actually resolves to through
     * the provider's own loader. Combined with the proof that the host declares exactly one provider
     * lambda, that pins the instance to the audited callsite.
     */
    static boolean isAuditedProvider(Object provider) {
        if (provider == null) return false;
        Class<?> lambda = provider.getClass();
        Class<?> nestHost = lambda.getNestHost();
        if (nestHost == null || !HOST.equals(nestHost.getName())) return false;
        try {
            return nestHost == Class.forName(HOST, false, lambda.getClassLoader());
        } catch (Throwable unresolvable) {
            return false;
        }
    }

    /**
     * Re-checked at dispatch: no foreign mixin may have transformed the audited host.
     *
     * <p>The audit reads the jar's original bytes. A mixin injecting into the decider changes what
     * actually runs, and could branch on the null world and position this certification passes in --
     * returning one answer during precompute and a different one in play, without ever throwing.
     * Neither the hash nor the local-slot proof can see that, and the override walk cannot either,
     * because the declared owner is unchanged.
     *
     * <p>Checked at dispatch rather than at registration because the scan has not run yet when mods
     * register their blocks.
     */
    static boolean hostNotTransformed() {
        return !ForeignMixinScanner.anyActiveClaimTargets(HOST);
    }
}
