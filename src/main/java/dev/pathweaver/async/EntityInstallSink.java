package dev.pathweaver.async;

import dev.pathweaver.PathWeaver;
import dev.pathweaver.config.PathWeaverConfig;
import dev.pathweaver.duck.PWNavigation;
import dev.pathweaver.gate.FabricLandPathRegistryLatch;
import net.minecraft.world.level.pathfinder.Path;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/**
 * Main-thread bridge from request-keyed async completions to live navigation. Installation requires
 * the exact request key plus unchanged entity UUID, world/dimension, navigation, path and target intent.
 */
public class EntityInstallSink implements ResultInstaller.InstallSink {
    public enum PendingDecision { NONE, PRESERVE, SUPERSEDE }

    private record Registration(RequestKey key, PWNavigation navigation,
                                NavigationIdentity identity, RequestTarget target,
                                boolean requiresEmptyLandRegistry) { }

    private static final RequestTarget UNSPECIFIED_TARGET =
        RequestTarget.of(Set.of(), 0, false, 0, 0.0F);
    private final Map<Integer, Registration> inFlight = new ConcurrentHashMap<>();
    /**
     * Evaluators still owed their {@code done()}, keyed by the exact request that prepared them.
     *
     * <p>Keyed by request rather than held on the navigation, because a superseded request's
     * evaluator outlives its registration: the navigation can dispatch again on the next tick, and a
     * navigation-scoped field would then run the epilogue against the *new* request's evaluator while
     * the old one silently kept the mob's search costs forever.
     */
    private final Map<RequestKey, OwedEpilogue> epilogues = new ConcurrentHashMap<>();

    /**
     * An owed {@code done()} plus the gate that says whether a worker ever reached its evaluator.
     *
     * <p>The gate is carried so a hard stop can still run the epilogues it is safe to run. Without
     * it, {@code clear(false)} had to abandon every owed epilogue, and an abandoned one is not a lost
     * optimisation: {@code AmphibiousNodeEvaluator.prepare} sets the mob's WALKABLE cost to 6.0 and
     * WATER_BORDER to 4.0, and only {@code done()} puts them back. A drowned or axolotl that was
     * mid-dispatch when the world closed kept those search-time costs for as long as it stayed
     * loaded, permanently and silently.
     */
    private record OwedEpilogue(net.minecraft.world.level.pathfinder.NodeEvaluator evaluator,
                                SearchStartGate gate) {}
    private final Map<Integer, Long> failUntilTick = new ConcurrentHashMap<>();
    private final AtomicBoolean callbackFailureLogged = new AtomicBoolean();
    private final AtomicBoolean rollbackFailureLogged = new AtomicBoolean();
    private final BooleanSupplier landRegistryAllowsInstall;
    /** Tick at which expired cooldown entries were last swept, so the map cannot grow forever. */
    private long lastCooldownSweepTick;
    private final AtomicBoolean installFailureLogged = new AtomicBoolean();
    private final AtomicBoolean epilogueDropLogged = new AtomicBoolean();
    private static final long FAIL_COOLDOWN_TICKS = 40L;
    private static final long COOLDOWN_SWEEP_INTERVAL_TICKS = 20L;
    private volatile long currentTick;

    public EntityInstallSink() {
        this(FabricLandPathRegistryLatch::allowsWalkInstall);
    }

    /** Test seam accepts an isolated ordering model and cannot reset production lifecycle state. */
    EntityInstallSink(BooleanSupplier landRegistryAllowsInstall) {
        this.landRegistryAllowsInstall = java.util.Objects.requireNonNull(landRegistryAllowsInstall);
    }

    public void setTick(long tick) { this.currentTick = tick; }

    /** Called from the interceptor on the main thread at dispatch time. */
    public void register(RequestKey key, PWNavigation navigation, RequestTarget target) {
        register(key, navigation, target, false);
    }

    /** Capture whether this exact request depends on Fabric's land-provider registry staying empty. */
    public void register(RequestKey key, PWNavigation navigation, RequestTarget target,
                         boolean requiresEmptyLandRegistry) {
        Registration next = new Registration(
            key, navigation, navigation.pathweaver$identity(), target, requiresEmptyLandRegistry);
        Registration existing = inFlight.putIfAbsent(key.entityId(), next);
        if (existing != null) {
            throw new IllegalStateException("Entity " + key.entityId()
                + " already has an accepted async path registration");
        }
    }

    /** Package-private helper for tests whose target identity is irrelevant. */
    void register(RequestKey key, PWNavigation navigation) {
        register(key, navigation, UNSPECIFIED_TARGET);
    }

    public boolean isRegistered(int entityId) {
        return inFlight.containsKey(entityId);
    }

    public boolean isRegistered(int entityId, PWNavigation navigation) {
        Registration registration = inFlight.get(entityId);
        return registration != null && registration.navigation() == navigation;
    }

    public PendingDecision pendingDecision(int entityId, PWNavigation navigation, RequestTarget target) {
        return pendingDecision(entityId, navigation, target, false);
    }

    public PendingDecision pendingDecision(int entityId, PWNavigation navigation, RequestTarget target,
                                           boolean recomputeInvalidated) {
        Registration registration = inFlight.get(entityId);
        if (registration == null) return PendingDecision.NONE;
        if (recomputeInvalidated) return PendingDecision.SUPERSEDE;
        if (registration.navigation() != navigation) return PendingDecision.SUPERSEDE;
        try {
            if (!registration.identity().sameLiveIdentity(navigation.pathweaver$identity())) {
                return PendingDecision.SUPERSEDE;
            }
        } catch (Throwable ignored) {
            return PendingDecision.SUPERSEDE;
        }
        return registration.target().equals(target)
            ? PendingDecision.PRESERVE : PendingDecision.SUPERSEDE;
    }

    /** Cancel the current exact navigation request because a materially different intent replaced it. */
    public boolean supersede(int entityId) {
        Registration registration = inFlight.get(entityId);
        if (registration == null || !inFlight.remove(entityId, registration)) return false;
        finishDiscard(registration, RequestOutcome.SUPERSEDED);
        return true;
    }

    /** Stop may invalidate only the registration owned by that exact navigation object. */
    public boolean cancel(int entityId, PWNavigation navigation) {
        Registration registration = inFlight.get(entityId);
        if (registration == null || registration.navigation() != navigation
                || !inFlight.remove(entityId, registration)) return false;
        finishDiscard(registration, RequestOutcome.NAVIGATION_STOPPED);
        return true;
    }

    /**
     * End a request without installing. The epilogue is deliberately NOT run here.
     *
     * <p>Superseding or stopping a navigation happens while its worker may still be searching, and
     * the evaluator's {@code done()} clears the two caches that search is reading and nulls the
     * context it is reading them through. Running it here raced the worker. The epilogue is owed
     * until {@link #runEpilogue} is called from the result-drain path, which cannot happen before
     * the worker has finished and handed its outcome back.
     */
    private void finishDiscard(Registration registration, RequestOutcome reason) {
        rollbackOptimisticTarget(registration);
        dev.pathweaver.PathWeaverRuntime.get().markOutcome(reason);
    }

    /**
     * Undo the optimistic targetPos written at dispatch. Every route that reaches here ended
     * without installing a path, so leaving it in place would pair the new target with the old
     * path and make vanilla's reuse short-circuit hand back a stale path forever.
     */
    /** Install threw: clear any partially-applied path AND restore the pre-dispatch target. */
    private void abortFailedInstall(Registration registration) {
        try {
            registration.navigation().pathweaver$abortFailedInstall();
        } catch (Throwable abortFailure) {
            if (rollbackFailureLogged.compareAndSet(false, true)) {
                try {
                    PathWeaver.LOG.warn("Aborting a failed path installation threw; the request was "
                        + "still discarded.", abortFailure);
                } catch (Throwable ignored) {
                    // Cancellation stays terminal even if the logging backend is compromised.
                }
            }
        }
    }

    private void rollbackOptimisticTarget(Registration registration) {
        try {
            registration.navigation().pathweaver$rollbackOptimisticTarget();
        } catch (Throwable rollbackFailure) {
            if (rollbackFailureLogged.compareAndSet(false, true)) {
                try {
                    PathWeaver.LOG.warn("Restoring the pre-dispatch navigation target threw; "
                        + "cancellation continued.", rollbackFailure);
                } catch (Throwable ignored) {
                    // Cancellation stays terminal even if the logging backend is compromised.
                }
            }
        }
    }

    /** Arm the epilogue for a request whose prologue has fully run on the main thread. */
    public void armEpilogue(RequestKey key,
                            net.minecraft.world.level.pathfinder.NodeEvaluator evaluator,
                            SearchStartGate gate) {
        epilogues.put(key, new OwedEpilogue(evaluator, gate));
    }

    /**
     * Run the epilogue owed by one request, on the main thread, exactly once.
     *
     * <p>Called from the drain path for every outcome, which is the earliest moment the worker is
     * provably done with this evaluator: the pool hands its result to the installer queue only after
     * the search callable has returned.
     */
    public void runEpilogue(RequestKey key) {
        OwedEpilogue owed = epilogues.remove(key);
        if (owed != null) finishCallback(owed.evaluator());
    }

    private void finishCallback(net.minecraft.world.level.pathfinder.NodeEvaluator evaluator) {
        try {
            evaluator.done();
        } catch (Throwable callbackFailure) {
            if (callbackFailureLogged.compareAndSet(false, true)) {
                try {
                    PathWeaver.LOG.warn("A mod callback threw while cancelling async pathfinding; "
                        + "the request was discarded and cancellation continued.", callbackFailure);
                } catch (Throwable ignored) {
                    // Cancellation must remain terminal even if the logging backend is compromised.
                }
            }
        }
    }

    /** Test seam: number of live sync-cooldown entries. */
    int cooldownEntryCount() { return failUntilTick.size(); }

    public boolean shouldForceSync(int entityId, long tick) {
        sweepExpiredCooldowns(tick);
        Long until = failUntilTick.get(entityId);
        if (until == null) return false;
        if (tick >= until) {
            failUntilTick.remove(entityId);
            return false;
        }
        return true;
    }

    /**
     * Drop cooldown entries whose deadline has passed.
     *
     * <p>An entry was previously removed only if that same entity asked again. A mob that failed a
     * search and then died, despawned or changed dimension never asks again, and entity ids are not
     * reused within a run, so on a long-lived server the map grew without bound. Sweeping is cheap
     * because the map is normally empty and is only walked once per second of server time.
     */
    private void sweepExpiredCooldowns(long tick) {
        if (failUntilTick.isEmpty() || tick - lastCooldownSweepTick < COOLDOWN_SWEEP_INTERVAL_TICKS) {
            return;
        }
        lastCooldownSweepTick = tick;
        failUntilTick.entrySet().removeIf(entry -> tick >= entry.getValue());
    }

    private Registration matching(RequestKey key) {
        Registration registration = inFlight.get(key.entityId());
        return registration != null && registration.key().equals(key) ? registration : null;
    }

    @Override
    public boolean isStale(RequestKey key, long dispatchTick, double x, double y, double z) {
        Registration registration = matching(key);
        if (registration == null) return true;
        if (registration.requiresEmptyLandRegistry() && !landRegistryAllowsInstall.getAsBoolean()) {
            return true;
        }
        long age = currentTick - dispatchTick;
        if (age < 0L || age > PathWeaverConfig.get().maxResultAgeTicks) return true;
        try {
            return !registration.identity().sameLiveIdentity(
                        registration.navigation().pathweaver$identity())
                || registration.navigation().pathweaver$stale(x, y, z);
        } catch (Throwable ignored) {
            return true;
        }
    }

    @Override
    public void install(RequestKey key, Path path) {
        Registration registration = matching(key);
        if (registration != null && inFlight.remove(key.entityId(), registration)) {
            try {
                registration.navigation().pathweaver$install(path);
                failUntilTick.remove(key.entityId());
                dev.pathweaver.PathWeaverRuntime.get().markOutcome(RequestOutcome.INSTALLED);
            } catch (Throwable installFailure) {
                // Installation calls vanilla moveTo, which foreign mixins can inject into, so a
                // throw here may leave a new or partially-applied path behind. Restoring only the
                // target would pair that path with the old target — the same broken invariant.
                // Abort clears the path and restores the target together.
                abortFailedInstall(registration);
                failUntilTick.put(key.entityId(), currentTick + FAIL_COOLDOWN_TICKS);
                dev.pathweaver.PathWeaverRuntime.get().markOutcome(RequestOutcome.INSTALL_FAILED);
                if (installFailureLogged.compareAndSet(false, true)) {
                    try {
                        PathWeaver.LOG.warn("Async path installation failed; the request was discarded "
                            + "and later requests temporarily run sync.", installFailure);
                    } catch (Throwable ignored) {
                        // Callback balance and failure cooldown must survive a broken logging backend.
                    }
                }
            }
        }
    }

    @Override
    public void discard(RequestKey key, RequestOutcome reason) {
        Registration registration = matching(key);
        if (registration != null && inFlight.remove(key.entityId(), registration)) {
            finishDiscard(registration, reason);
        }
    }

    @Override
    public void noPath(RequestKey key) {
        Registration registration = matching(key);
        if (registration != null && inFlight.remove(key.entityId(), registration)) {
            finishDiscard(registration, RequestOutcome.NO_PATH);
        }
    }

    @Override
    public void failed(RequestKey key, Throwable failure) {
        Registration registration = matching(key);
        if (registration != null && inFlight.remove(key.entityId(), registration)) {
            finishDiscard(registration, RequestOutcome.SEARCH_FAILED);
            failUntilTick.put(key.entityId(), currentTick + FAIL_COOLDOWN_TICKS);
        }
    }

    public int inFlightCount() { return inFlight.size(); }

    /** Forget registrations/cooldowns at a server boundary. Late results cannot match a future key. */
    public void clear() {
        clear(true);
    }

    /**
     * @param workersQuiesced whether every worker has finished, so their evaluators are safe to touch
     */
    public void clear(boolean workersQuiesced) {
        for (Registration registration : inFlight.values().toArray(Registration[]::new)) {
            if (inFlight.remove(registration.key().entityId(), registration)) {
                finishDiscard(registration, RequestOutcome.SERVER_RESET);
            }
        }
        // Only when the pool actually went quiet. This previously assumed it had, because the runtime
        // shuts the pool down first -- but shutdownNow() interrupts without waiting, so a worker
        // could still be mid-search. A real server stop caught exactly that: done() nulled the
        // evaluator's mob while its own worker was still reading it, and the search died on an NPE.
        // Dropping an epilogue here costs nothing that survives the boundary; racing one does.
        if (workersQuiesced) {
            for (RequestKey key : epilogues.keySet().toArray(RequestKey[]::new)) runEpilogue(key);
        } else {
            // Abandon only what is genuinely unsafe. An epilogue whose gate was never opened cannot
            // be racing anything -- no worker was ever authorized to read that evaluator -- so its
            // done() can still run and give the mob its pathfinding costs back. Abandoning those too
            // was over-broad: an abandoned amphibious epilogue leaves WALKABLE at 6.0 and
            // WATER_BORDER at 4.0 on a live mob for as long as it stays loaded, which outlives the
            // shutdown this was protecting.
            int abandoned = 0;
            int restored = 0;
            for (RequestKey key : epilogues.keySet().toArray(RequestKey[]::new)) {
                OwedEpilogue owed = epilogues.get(key);
                if (owed == null) continue;
                if (owed.gate().authorizedSearch()) {
                    epilogues.remove(key);
                    abandoned++;
                } else {
                    runEpilogue(key);
                    restored++;
                }
            }
            epilogues.clear();
            if (abandoned > 0 && epilogueDropLogged.compareAndSet(false, true)) {
                try {
                    PathWeaver.LOG.warn("Workers did not stop in time; {} pathfinding epilogue(s) "
                        + "abandoned rather than run against evaluators still in use"
                        + "{}.", abandoned,
                        restored > 0 ? ", " + restored + " restored because no worker had started" : "");
                } catch (Throwable ignored) {
                    // The boundary must stay terminal even if the logging backend is compromised.
                }
            }
        }
        failUntilTick.clear();
        // Reset the sweep clock too. A new server starts its tick count near zero, so a
        // timestamp left from a long previous run would suppress sweeping until the new
        // server had been up as long as the old one.
        lastCooldownSweepTick = 0L;
    }
}
