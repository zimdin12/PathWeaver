package dev.pathweaver.gametest;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;

/**
 * Entity types by registry id, because the constants that used to name them are version-specific.
 *
 * <p>Minecraft 26.1.2 exposes {@code EntityType.ZOMBIE} and friends as {@code EntityType} constants.
 * 26.2 removed them — the class went from 180 entity constants to 10 — and replaced them with
 * {@code EntityTypeIds.ZOMBIE}, a {@code ResourceKey} that has to be resolved through the registry.
 * A test source naming either one compiles against exactly one version.
 *
 * <p>This resolves by registry id instead, which both versions agree on. The lookup deliberately
 * goes through {@code EntityType.getKey(...).toString()} rather than the registry's own id-typed
 * accessor: 26.2 also renamed {@code ResourceLocation} to {@code Identifier}, so naming that type
 * would reintroduce exactly the problem being solved, while {@code toString} is declared on Object.
 *
 * <p>Test-only. Nothing in the shipped mod looks a mob type up by name.
 */
final class VanillaTypes {

    private VanillaTypes() {}

    /** The registry ids this suite uses, so a typo is one constant rather than one per call site. */
    static final String ZOMBIE = "minecraft:zombie";
    static final String VILLAGER = "minecraft:villager";
    static final String SPIDER = "minecraft:spider";
    static final String DROWNED = "minecraft:drowned";
    static final String BEE = "minecraft:bee";
    static final String COD = "minecraft:cod";

    /**
     * The mob type registered under {@code id}.
     *
     * <p>Throws rather than returning null: a missing type means the id is wrong or the registry is
     * not populated yet, and both produce a far more confusing failure at the spawn call.
     */
    @SuppressWarnings("unchecked")
    static <E extends Mob> EntityType<E> mob(String id) {
        for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
            if (EntityType.getKey(type).toString().equals(id)) return (EntityType<E>) type;
        }
        throw new IllegalStateException("no entity type registered as " + id
            + " -- id wrong, or the registry is not populated at this point in the test");
    }
}
