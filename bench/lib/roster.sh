# The harness roster, defined once. bench/harness-roster.sh runs it and bench/harness-receipt.sh
# certifies it. The receipt used to keep its own list: when the lod harness was added to the roster
# and not to that list, a series where lod failed would still have been certified "all 8 rows".
#
# name : gradle flag : the source manifest that harness is supposed to run
ROSTER=(
  "stock::src/gametest/resources/fabric.mod.json"
  "auditedTier:-PauditedTierHarness:src/gametest/auditedResources/fabric.mod.json"
  "unsafeTier:-PunsafeTierHarness:src/gametest/unsafeResources/fabric.mod.json"
  "newFamily:-PnewFamilyHarness:src/gametest/newFamilyResources/fabric.mod.json"
  "auditedRouting:-PauditedRoutingHarness:src/gametest/auditedRoutingResources/fabric.mod.json"
  "refused:-PrefusedHarness:src/gametest/refusedResources/fabric.mod.json"
  "breaker:-PbreakerHarness:src/gametest/breakerResources/fabric.mod.json"
  "fabricAggregate:-PfabricAggregateHarness:src/gametest/aggregateResources/fabric.mod.json"
  "lod:-PlodHarness:src/gametest/lodResources/fabric.mod.json"
)
