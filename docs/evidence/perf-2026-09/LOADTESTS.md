# Load tests, 2026-09-12

Clean Fabric 26.1.2 server, only the mods each case names. Home directories in these logs are
replaced with <USER> and <HOME>; nothing else is edited.

loadtest-1-fabricapi-only.log  Fabric API alone. Reaches "Done" - the positive control, so a
                               later "does not start" is a fact about a mod and not about the test.
loadtest-4-pathwright.log      Fabric API + Cloth Config + Pathwright 1.0.3. Critical mixin
                               injection failure on ServerLevel.setBlock, never reaches "Done".

The two PathWeaver rows of that table are in docs/COMPARISON-PATHWRIGHT.md. Ours also failed the
first time, for a declared missing dependency, and starts once Cloth Config is present.
