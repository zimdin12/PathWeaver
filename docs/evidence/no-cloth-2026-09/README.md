# Cloth Config made optional, verified by booting a server without it

A clean Fabric 26.1.2 server, only the mods each case names. Home directories replaced with <USER>
and <HOME>; nothing else edited.

| file | mods | result |
|---|---|---|
| `1-fabricapi-only-STARTS.log` | Fabric API | starts — the positive control |
| `2-pathweaver-before-fix-FAILS.log` | + PathWeaver, no Cloth | `NoClassDefFoundError`, never starts |
| `3-pathweaver-after-fix-STARTS.log` | + PathWeaver, no Cloth | starts, and writes the config file |
| `4-pathweaver-at-release-STARTS.log` | + PathWeaver at `a695c38`, sha256 `63d7d123...`, no Cloth | starts, all 6 families active, writes the config file |
| `5-pathweaver-hotpath-fixes-STARTS.log` | + PathWeaver at `aa65791`, sha256 `0ba3d71a...`, no Cloth | starts, all 6 families active, writes the config file |
| `6-pathweaver-release-jar-STARTS.log` | + PathWeaver at `013dcf6` built from a fresh checkout, sha256 `47e24d24...`, no Cloth | starts, all 6 families active, writes the same config file |
| `first-run-config.json` | | what row 4 wrote on its first run |

Row 6 is the jar that ships. Row 5's jar was built in a checkout where `pathweaver.mixins.json` had CRLF
line endings on disk, so it differs from a fresh checkout's build in that one file's line endings and
nothing else (`../RELEASE-JARS-0.9.0.txt`). Row 5 was added on 2026-09-14, on the jar carrying the per-node hot-path fixes; the config it wrote on
its first run is identical to `first-run-config.json`. Row 4 was added on 2026-09-13, on the jar carrying both LOD fixes. Row 3's first run wrote
`lodIntervalTicks: 10`, the default before the first LOD fix raised it to 40. Kept, that file would have
shown a server owner a default the mod no longer has, so it was replaced by row 4's, which differs in
that one value.

The middle row is the point. The whole test suite was green when it was taken. Dropping the declared
dependency and removing `implements ConfigData` was not enough, because `CompatibilityTier` and
`PathCacheMode` implemented `SelectionListEntry.Translatable`, a Cloth GUI interface, so initialising
`PathWeaverConfig` resolved a GUI class on a server that had none.

No unit test could see this and none ever will: the test classpath has Cloth on it. What now stands
in for this boot is `NoClothOnTheServerPathTest`, which reads the compiled bytes of the classes a
server loads and refuses a `clothconfig2` reference in any of them, with `ClothScreen` as a positive
control so a silent search cannot pass for a clean one.

The first-run config write is also only visible here. AutoConfig used to create the file; after the
change nothing did, the mod worked perfectly, and the file the project page tells a server owner to
edit was never created.
