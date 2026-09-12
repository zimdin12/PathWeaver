# Cloth Config made optional, verified by booting a server without it

A clean Fabric 26.1.2 server, only the mods each case names. Home directories replaced with <USER>
and <HOME>; nothing else edited.

| file | mods | result |
|---|---|---|
| `1-fabricapi-only-STARTS.log` | Fabric API | starts — the positive control |
| `2-pathweaver-before-fix-FAILS.log` | + PathWeaver, no Cloth | `NoClassDefFoundError`, never starts |
| `3-pathweaver-after-fix-STARTS.log` | + PathWeaver, no Cloth | starts, and writes the config file |
| `first-run-config.json` | | what it wrote on that first run |

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
