package dev.pathweaver.gate;

import net.fabricmc.fabric.api.registry.LandPathTypeRegistry;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.PathType;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Precomputed answers for land path-type providers that cannot depend on the world.
 *
 * <p>A mod that tells Minecraft "mobs should treat my block as dangerous" is not modifying
 * pathfinding code, it is using a public Fabric API. Those mods are an open-ended set — any mod may
 * call the API — so auditing them one at a time is the wrong shape of solution. Previously a single
 * such registration switched PathWeaver off entirely, because a worker skips the live provider map
 * and would therefore route a mob straight over the block the mod marked dangerous.
 *
 * <p>{@link LandPathTypeRegistry.StaticPathTypeProvider} takes only a {@link BlockState} and a
 * neighbour flag: no {@code BlockGetter}, no {@code BlockPos}. It is structurally incapable of
 * reading the world or varying by position, and its input domain — every state a block can have,
 * times two — is finite and enumerable. So the provider is called here, on the main thread, once
 * per input, and the answers are frozen into an immutable table.
 *
 * <p>The worker then reads that table instead of calling anything. Third-party provider code never
 * executes off-thread, which means this needs no bytecode audit, no artifact hash, and no per-mod
 * entry. Any mod using the static form works, including ones written after this code.
 *
 * <p>{@link LandPathTypeRegistry.DynamicPathTypeProvider} does receive the world and is not handled
 * here; such a registration still denies Walk through
 * {@link FabricLandPathRegistryLatch#beforeProviderMutation()}.
 *
 * <p>Residual limitation: a static provider that closes over mutable state and changes its answer
 * after registration would leave this table stale. That is a weak concern in practice — Lithium
 * already caches path types per block state eagerly at startup, so a provider that is not stable is
 * already misbehaving on any pack running Lithium — but it is a real assumption and not a proof.
 */
public final class CertifiedLandProviders {
    /** Answers for [state][neighbour], published as one immutable snapshot per registration. */
    private record Table(Map<BlockState, PathType> direct, Map<BlockState, PathType> neighbour) {}

    private static volatile Table published = new Table(Map.of(), Map.of());

    private CertifiedLandProviders() {}

    /**
     * Main thread: evaluate a static provider across its whole input domain and publish the result.
     *
     * <p>Returns false if anything prevents a complete evaluation, in which case the caller must
     * fall back to denying, because a partial table would answer some states and silently differ
     * from vanilla on the rest.
     */
    public static boolean certify(Block block,
                                 LandPathTypeRegistry.StaticPathTypeProvider provider) {
        try {
            // HashMap, not Map.of/copyOf: a provider returning null for a state is legal and
            // means "no opinion, use vanilla". The immutable factories reject null values, which
            // would have failed certification for exactly the providers most likely to exist.
            Map<BlockState, PathType> direct = new HashMap<>();
            Map<BlockState, PathType> neighbour = new HashMap<>();
            for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                direct.put(state, provider.getPathType(state, false));
                neighbour.put(state, provider.getPathType(state, true));
            }
            Table current = published;
            Map<BlockState, PathType> mergedDirect = new HashMap<>(current.direct());
            Map<BlockState, PathType> mergedNeighbour = new HashMap<>(current.neighbour());
            mergedDirect.putAll(direct);
            mergedNeighbour.putAll(neighbour);
            // Publish one fully built snapshot. A worker either sees the old table or the new one,
            // never a half-populated map.
            published = new Table(Collections.unmodifiableMap(mergedDirect),
                Collections.unmodifiableMap(mergedNeighbour));
            return true;
        } catch (Throwable failure) {
            return false;
        }
    }

    /**
     * Main thread: freeze a dynamic provider that has been proven not to read the world.
     *
     * <p>Only for providers carrying an exact audit establishing that the {@code BlockGetter} and
     * {@code BlockPos} are never loaded — see {@link FarmersDelightStoveCompatibility}. Given that,
     * passing null for both is not a guess about behaviour: the arguments are provably dead, so the
     * provider's answer over the finite state domain is complete and stable, exactly as for the
     * static form.
     *
     * <p>Never call this on an unaudited dynamic provider. A provider that merely happens not to
     * throw on null could still be branching on it, and would then answer differently under a real
     * world while this table claimed otherwise.
     */
    private static boolean certifyWorldIndependentDynamic(
            Block block, LandPathTypeRegistry.DynamicPathTypeProvider provider) {
        return certify(block, (state, isNeighbour) -> provider.getPathType(state, null, null, isNeighbour));
    }

    /**
     * Certify a dynamic provider only if an exact audit covers this provider, this artifact, and this
     * concrete block.
     *
     * <p>All three conditions are required and each closes a different hole: the identity check that
     * this really is the audited lambda, the artifact audit that its implementation ignores the world,
     * and the per-block override check that no subclass from another mod is dispatched to instead.
     */
    public static boolean certifyAuditedDynamic(
            Block block, LandPathTypeRegistry.DynamicPathTypeProvider provider) {
        if (block == null || provider == null) return false;
        if (!FarmersDelightStoveCompatibility.isAuditedProvider(provider)) return false;
        if (!FarmersDelightStoveCompatibility.providerIsWorldIndependent()) return false;
        if (!FarmersDelightStoveCompatibility.deciderNotOverriddenFor(block.getClass())) return false;
        return certifyWorldIndependentDynamic(block, provider);
    }

    /** True when every state of this block has a frozen answer. */
    public static boolean isCertified(Block block) {
        Table table = published;
        for (BlockState state : block.getStateDefinition().getPossibleStates()) {
            if (!table.direct().containsKey(state) || !table.neighbour().containsKey(state)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Worker-safe substitute. Fabric asks for a provider and then calls it, so returning this makes
     * the frozen answer flow through Fabric's own dispatch without the mod's code running.
     */
    public static LandPathTypeRegistry.StaticPathTypeProvider frozenProvider() {
        return (state, isNeighbour) -> {
            Table table = published;
            return isNeighbour ? table.neighbour().get(state) : table.direct().get(state);
        };
    }

    /** Test seam: number of frozen block states. */
    static int certifiedStateCount() {
        return published.direct().size();
    }

    /** Test seam: drop all frozen answers. Never called in production. */
    static void resetForTests() {
        published = new Table(Map.of(), Map.of());
    }

    /** Test seam so the pure merge/enumeration logic can be exercised without a live registry. */
    static Map<BlockState, PathType> frozenDirectAnswers() {
        return new HashMap<>(published.direct());
    }
}
