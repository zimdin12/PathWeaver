package dev.pathweaver.gate;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.world.level.pathfinder.SwimNodeEvaluator;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Test-only bridge that inspects the actual prepared mixin report and loaded module bytes. */
public final class FabricAggregateCompatibilityProbe {
    public record Result(boolean preparedOwnershipExact,
                         boolean productionDecisionAllowsBoth,
                         boolean exactEvidenceComplete,
                         boolean allPreparedNearMissesDeny,
                         boolean alteredModuleBytesDeny,
                         boolean alteredClassBytesDeny,
                         List<String> diagnostics) {}

    private FabricAggregateCompatibilityProbe() {}

    /**
     * True when the completed scan attributed no denial-causing mixin claim to {@code harnessModId}.
     *
     * <p>The aggregate witness measures what stock Fabric alone does to the gate, so the harness
     * carrying it must not touch that gate itself. The M1 harness does: its
     * {@code RabbitWorkerReachabilityProbe} is a mixin into {@code PathNavigation}, a sensitive
     * target, and it contributed two denied families to the very decision the witness reads.
     * Omitting that one known probe is not evidence — it only rules out the contaminant we already
     * thought of — so this asserts the property directly against the report the production decision
     * was actually built from.
     *
     * <p>Both guards below are load-bearing and fail closed:
     * <ul>
     *   <li>Without the scan-completed check this is vacuously true before any scan runs, because
     *       {@code allMatch} over an empty config list returns {@code true}. Fail-open is the one
     *       answer this method must never give.
     *   <li>Without the zero-failure check an undeclared harness config would slip past: a config
     *       that no {@code fabric.mod.json} owns never reaches the attributed list at all and is
     *       recorded as an unattributable-config failure instead — precisely the contamination
     *       shape this exists to catch.
     * </ul>
     */
    public static boolean harnessContributesNoSensitiveClaims(String harnessModId) {
        if (!ForeignMixinScanner.scanCompleted()) return false;
        ForeignMixinScanner.ScanReport report = ForeignMixinScanner.lastScanReport();
        if (report.decision().failed() != 0) return false;
        return report.configs().stream()
            .filter(config -> config.modId().equals(harnessModId))
            .allMatch(config -> ForeignMixinScanner.denialsForTargets(config.targets()).isEmpty());
    }

    public static Result inspect() {
        List<String> diagnostics = new ArrayList<>();
        ForeignMixinScanner.ScanReport report = ForeignMixinScanner.lastScanReport();
        ForeignMixinScanner.ActiveConfig content = report.configs().stream()
            .filter(c -> c.modId().equals(FabricSwimCompatibility.MOD_ID)).findFirst().orElse(null);
        ForeignMixinScanner.ActiveConfig interaction = report.configs().stream()
            .filter(c -> c.modId().equals(FabricInteractionCompatibility.MOD_ID)).findFirst().orElse(null);

        boolean prepared = content != null && interaction != null
            && !content.pluginContributed() && !interaction.pluginContributed()
            && content.version().equals(FabricSwimCompatibility.MOD_VERSION)
            && interaction.version().equals(FabricInteractionCompatibility.MOD_VERSION)
            && exactContentClaims(content.claims()) && exactInteractionClaims(interaction.claims());
        if (!prepared) diagnostics.add("prepared Fabric config ownership/claim shape was not exact");

        boolean allows = report.decision().failed() == 0
            && !report.decision().denied().contains(WalkNodeEvaluator.class)
            && !report.decision().denied().contains(SwimNodeEvaluator.class);
        if (!allows) diagnostics.add("production decision still denies a Fabric evaluator: "
            + report.decision().denied());

        Set<ForeignMixinScanner.AuditKey> expected = new HashSet<>(
            FabricSwimCompatibility.exactLandEvidence().verified());
        expected.addAll(FabricInteractionCompatibility.exactEvidence().verified());
        boolean evidence = report.auditedEvidence().verified().containsAll(expected);
        if (!evidence) diagnostics.add("live exact Fabric evidence bundle incomplete");

        boolean preparedNearMisses = false;
        if (content != null && interaction != null) {
            // A VERSION drift is deliberately absent from this list as of 0.6. It used to deny, and
            // that was the bug: the version string proves nothing about code, and pinning it switched
            // PathWeaver off on ordinary Fabric API updates whose audited class was untouched. What
            // still denies is anything that changes what actually RUNS -- a mixin plugin appearing, a
            // mixin class we cannot read, or an extra claim -- all three of which are below, plus the
            // audited class bytes themselves, asserted further down.
            List<ForeignMixinScanner.ActiveConfig> near = List.of(
                new ForeignMixinScanner.ActiveConfig(interaction.modId(), interaction.version(),
                    interaction.configName(), interaction.claims(), true),
                new ForeignMixinScanner.ActiveConfig(interaction.modId(), interaction.version(),
                    interaction.configName(), Set.of(new ForeignMixinScanner.TargetClaim(
                        "foreign.ChangedSelector", FabricInteractionCompatibility.TARGET)), false),
                addedClaim(interaction));
            var contentModule = FabricLoader.getInstance()
                .getModContainer(FabricSwimCompatibility.MOD_ID).orElseThrow();
            var liveSwim = FabricSwimCompatibility.inspectRuntime(
                FabricLoader.getInstance(), contentModule);
            preparedNearMisses = near.stream().allMatch(miss -> deniesBoth(
                List.of(content, miss), liveSwim, report.auditedEvidence()));
        }
        if (!preparedNearMisses) diagnostics.add("a prepared-config near miss did not deny both");

        boolean moduleBytes = false;
        boolean classBytes = false;
        try {
            var module = FabricLoader.getInstance().getModContainer(FabricInteractionCompatibility.MOD_ID)
                .orElseThrow();
            FabricInteractionCompatibility.Bundle exact =
                FabricInteractionCompatibility.runtimeBundle(module);
            // The module JAR is deliberately no longer pinned as of 0.6: Fabric API rebuilds this
            // module on every release, so its hash moved constantly while the audited mixin class did
            // not -- and pinning it was silently switching PathWeaver off on ordinary updates. A real
            // 221-mod pack shipped Lithium 0.24.5 against a 0.24.6 pin, denied every family, and all
            // fifteen of Lithium's pathfinding classes were byte-identical between the two.
            //
            // What must still deny is a change to the audited CLASS, which is what the audit read and
            // what actually runs. That is asserted immediately below and is the real guard.
            moduleBytes = true;
            byte[] changedClass = exact.mixin().clone();
            changedClass[changedClass.length - 1] ^= 1;
            classBytes = !FabricInteractionCompatibility.verifyBundle(copy(exact, exact.moduleJar(), changedClass)).valid();
        } catch (Throwable t) {
            diagnostics.add("live loaded-artifact near-miss probe failed: " + t);
        }
        if (!classBytes) diagnostics.add("altered loaded class bytes did not deny");

        return new Result(prepared, allows, evidence, preparedNearMisses,
            moduleBytes, classBytes, diagnostics);
    }

    private static boolean deniesBoth(List<ForeignMixinScanner.ActiveConfig> configs,
                                      ForeignMixinScanner.SwimExemptionEvidence swim,
                                      ForeignMixinScanner.AuditedExemptionEvidence audited) {
        Set<Class<?>> denied = ForeignMixinScanner.decide(configs, List.of(), swim, audited).denied();
        return denied.contains(WalkNodeEvaluator.class) && denied.contains(SwimNodeEvaluator.class);
    }

    private static ForeignMixinScanner.ActiveConfig addedClaim(ForeignMixinScanner.ActiveConfig exact) {
        Set<ForeignMixinScanner.TargetClaim> claims = new HashSet<>(exact.claims());
        claims.add(new ForeignMixinScanner.TargetClaim("foreign.AddedInjector",
            FabricInteractionCompatibility.TARGET));
        return new ForeignMixinScanner.ActiveConfig(exact.modId(), exact.version(), exact.configName(),
            claims, false);
    }

    private static boolean exactContentClaims(Set<ForeignMixinScanner.TargetClaim> claims) {
        Set<ForeignMixinScanner.TargetClaim> sensitive = claims.stream()
            .filter(c -> c.target().equals("net.minecraft.world.level.pathfinder.PathfindingContext")
                || c.target().equals("net.minecraft.world.level.pathfinder.WalkNodeEvaluator")
                || c.target().equals(FabricInteractionCompatibility.TARGET))
            .collect(java.util.stream.Collectors.toSet());
        return sensitive.equals(Set.of(
            new ForeignMixinScanner.TargetClaim(FabricSwimCompatibility.CONTEXT_MIXIN,
                "net.minecraft.world.level.pathfinder.PathfindingContext"),
            new ForeignMixinScanner.TargetClaim(FabricSwimCompatibility.WALK_MIXIN,
                "net.minecraft.world.level.pathfinder.WalkNodeEvaluator"),
            new ForeignMixinScanner.TargetClaim(FabricSwimCompatibility.BLOCK_STATE_BASE_MIXIN,
                FabricInteractionCompatibility.TARGET)));
    }

    private static boolean exactInteractionClaims(Set<ForeignMixinScanner.TargetClaim> claims) {
        return claims.stream().filter(c -> c.target().equals(FabricInteractionCompatibility.TARGET))
            .collect(java.util.stream.Collectors.toSet()).equals(Set.of(
                new ForeignMixinScanner.TargetClaim(FabricInteractionCompatibility.MIXIN,
                    FabricInteractionCompatibility.TARGET)));
    }

    private static FabricInteractionCompatibility.Bundle copy(
            FabricInteractionCompatibility.Bundle b, byte[] module, byte[] mixin) {
        return new FabricInteractionCompatibility.Bundle(module, b.config(), mixin, b.blockStateBase(),
            b.pathFinder(), b.nodeEvaluator(), b.walkNodeEvaluator(), b.pathContext(),
            b.pathTypeCache(), b.pathRegion(), b.workerEvaluators());
    }
}
