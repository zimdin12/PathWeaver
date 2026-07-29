package dev.pathweaver.gate;

import dev.pathweaver.config.CompatibilityTier;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Exact runtime fingerprints and an ASM proof for the Diagonal Blocks pathfinding mixin.
 *
 * <p>Diagonal Blocks is shipped as a nested library inside Diagonal Fences, Walls and Windows, and
 * it overrides {@code WalkNodeEvaluator.isDiagonalValid} so diagonal fence and wall connections are
 * pathable. That override runs inside the worker's search, so it decides whether PathWeaver may run
 * at all on any pack containing those mods.
 *
 * <p>The override itself is clean: it performs no field write, and reads block state only through
 * the {@code PathfindingContext} the search already owns, plus one static lookup map that is
 * {@code static final}, built once in a class initializer from {@code Maps.immutableEnumMap}, and
 * never written afterwards.
 *
 * <p>The reason this needs a real proof rather than a glance is what sits next to that map.
 * {@code StarCollisionBlock} also holds {@code CORNER_SHAPES_CACHE} and
 * {@code CORNER_SHAPES_BLOCK_CACHE}, which are plain unsynchronized fastutil maps mutated lazily on
 * the collision-shape path. Touching either from a worker would be a genuine data race — the exact
 * hazard this mod is otherwise cleared of. The audited override never calls into that class at all,
 * and the verification below enforces that mechanically: the mixin's entire static-field-read
 * surface must be the immutable map, and it may make no call into {@code StarCollisionBlock}. If a
 * future version routes the diagonal check through a shape cache, the exemption fails closed rather
 * than silently inheriting this finding.
 *
 * <p>Like Lithium, this is a {@link CompatibilityTier#AUDITED} exemption rather than a
 * {@link CompatibilityTier#STRICT} one: no worker write is possible, but the override adds live
 * block reads, so a search racing a block change can return a worse path.
 */
final class DiagonalBlocksCompatibility {
    static final String MOD_ID = "diagonalblocks";
    static final String MOD_VERSION = "26.1.0";
    static final String CONFIG = "diagonalblocks.common.mixins.json";
    private static final String PACKAGE = "fuzs.diagonalblocks.common.mixin";
    static final String WALK_MIXIN = PACKAGE + ".WalkNodeEvaluatorMixin";
    static final String WALK = "net.minecraft.world.level.pathfinder.WalkNodeEvaluator";

    private static final String MINECRAFT_VERSION = "26.1.2";
    private static final String MODULE_SHA =
        "df59211601dc83718ec0189a56c9f5569a0654f56a58fbbd644ea462a51b74d6";
    private static final String CONFIG_SHA =
        "8aeca65fac6618bb8d7c266c5b4194af876a963fabd77c55f86c9131abfe6ea8";
    private static final String WALK_MIXIN_SHA =
        "fb5324c681fac2f33145fc67560f2162059353ab5e010d29405af1119d063381";

    private static final String STAR_COLLISION_BLOCK =
        "fuzs/diagonalblocks/common/api/v2/block/StarCollisionBlock";
    /** The only shared state the audited override may read: an immutable, class-init-once map. */
    private static final String IMMUTABLE_PROPERTY_MAP =
        STAR_COLLISION_BLOCK + ".PROPERTY_BY_DIRECTION";

    private DiagonalBlocksCompatibility() {}

    record Bundle(byte[] moduleJar, byte[] config, byte[] walkMixin) {}

    /** Tier-gated exactly as Lithium is: below AUDITED no evidence is produced, so denial stands. */
    static ForeignMixinScanner.AuditedExemptionEvidence inspectRuntime(
            FabricLoader loader, ModContainer module, CompatibilityTier tier) {
        try {
            String id = module.getMetadata().getId();
            if (!MOD_ID.equals(id)) {
                return ForeignMixinScanner.AuditedExemptionEvidence.unverified(
                    id + " is not the Diagonal Blocks compatibility owner");
            }
            if (!tier.allowsAudited()) {
                return ForeignMixinScanner.AuditedExemptionEvidence.unverified(
                    "Diagonal Blocks modifies pathfinding; compatibilityTier=" + tier
                        + " keeps it synchronous. Set compatibilityTier=AUDITED to allow it.");
            }
            String version = module.getMetadata().getVersion().getFriendlyString();
            String minecraft = loader.getModContainer("minecraft")
                .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("missing");
            if (!MINECRAFT_VERSION.equals(minecraft)) {
                return unverified("exact audit unsupported on Minecraft " + minecraft);
            }
            if (!MOD_VERSION.equals(version)) return unverified("unsupported version " + version);

            List<String> diagnostics = verify(runtimeBundle(module));
            return diagnostics.isEmpty() ? exactEvidence()
                : new ForeignMixinScanner.AuditedExemptionEvidence(Set.of(), diagnostics);
        } catch (Throwable t) {
            return ForeignMixinScanner.AuditedExemptionEvidence.unverified(
                "Diagonal Blocks exact audit failed: " + t);
        }
    }

    static Bundle runtimeBundle(ModContainer module) throws java.io.IOException {
        return new Bundle(
            AuditedMixinCompatibility.readModuleArtifact(module),
            AuditedMixinCompatibility.readModResource(module, CONFIG),
            AuditedMixinCompatibility.readModResource(module,
                AuditedMixinCompatibility.classResource(WALK_MIXIN)));
    }

    static ForeignMixinScanner.AuditedExemptionEvidence exactEvidence() {
        return new ForeignMixinScanner.AuditedExemptionEvidence(
            Set.of(new ForeignMixinScanner.AuditKey(MOD_ID, MOD_VERSION, CONFIG,
                WALK_MIXIN, WALK, null)),
            List.of());
    }

    static List<String> verify(Bundle bundle) {
        List<String> diagnostics = new ArrayList<>();
        AuditedMixinCompatibility.checkHash("Diagonal Blocks module jar", bundle.moduleJar(),
            MODULE_SHA, diagnostics);
        AuditedMixinCompatibility.checkHash("Diagonal Blocks mixin config", bundle.config(),
            CONFIG_SHA, diagnostics);
        AuditedMixinCompatibility.checkHash("Diagonal Blocks WalkNodeEvaluatorMixin",
            bundle.walkMixin(), WALK_MIXIN_SHA, diagnostics);
        try {
            AuditedMixinCompatibility.verifyConfig(bundle.config(), PACKAGE, null,
                "WalkNodeEvaluatorMixin", diagnostics);
            AuditedMixinCompatibility.requireMixinTarget(
                AuditedMixinCompatibility.classNode(bundle.walkMixin()),
                "net/minecraft/world/level/pathfinder/WalkNodeEvaluator", diagnostics);
            verifyOverrideTouchesNoMutableSharedState(bundle.walkMixin(), diagnostics);
        } catch (Throwable t) {
            diagnostics.add("Diagonal Blocks ASM/config shape parse failed: " + t);
        }
        return diagnostics;
    }

    /**
     * Require that the override writes nothing, reads no shared state beyond the immutable map, and
     * never reaches the unsynchronized shape caches that live alongside that map.
     */
    static void verifyOverrideTouchesNoMutableSharedState(byte[] bytes,
                                                          List<String> diagnostics) {
        ClassNode node = AuditedMixinCompatibility.classNode(bytes);
        Set<String> staticReads = new HashSet<>();
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                int opcode = insn.getOpcode();
                if (insn instanceof FieldInsnNode field) {
                    if (opcode == Opcodes.PUTFIELD || opcode == Opcodes.PUTSTATIC) {
                        diagnostics.add("Diagonal Blocks override writes shared state: "
                            + method.name + method.desc + " -> " + field.owner + "." + field.name);
                    } else if (opcode == Opcodes.GETSTATIC) {
                        staticReads.add(field.owner + "." + field.name);
                    }
                } else if (insn instanceof MethodInsnNode call
                        && STAR_COLLISION_BLOCK.equals(call.owner)) {
                    // The unsynchronized CORNER_SHAPES caches are reached through this class; the
                    // audited override must never enter it.
                    diagnostics.add("Diagonal Blocks override reaches the shape-cache owner: "
                        + method.name + method.desc + " -> " + call.name + call.desc);
                }
            }
        }
        Set<String> unexpected = new HashSet<>(staticReads);
        unexpected.remove(IMMUTABLE_PROPERTY_MAP);
        if (!unexpected.isEmpty()) {
            diagnostics.add("Diagonal Blocks override reads unaudited shared state: " + unexpected);
        }
    }

    private static ForeignMixinScanner.AuditedExemptionEvidence unverified(String reason) {
        return ForeignMixinScanner.AuditedExemptionEvidence.unverified(
            "Diagonal Blocks exact audit: " + reason);
    }
}
