# Cloth Config optional on 26.3, verified by booting a server without it

Measured 2026-10-13 on clean Fabric 26.3 servers (loader 0.19.5, launcher 1.1.2), each started from
an empty directory with a flat world and only the mods its row names. Paths are replaced with
`<SERVER>`, `<HOME>` and `<USER>`; nothing else is edited.

| file | mods | result |
|---|---|---|
| `1-fabricapi-only-STARTS.log` | Fabric API 0.162.0+26.3 | starts, `Done (0.575s)` |
| `2-pathweaver-no-cloth-STARTS.log` | + PathWeaver 0.9.0+26.3 from a fresh clone at `0843903` (sha256 `7905f731...`) | starts, `PathWeaver is ACTIVE: all 6 movement families can path off-thread`, `Done (0.421s)` |
| `3-CONTROL-cloth-interface-FAILS.log` | + the same source with one line changed (sha256 `46577bd6...`) | never starts |
| `first-run-config.json` | | what row 2 wrote; parses to the same JSON as `../../no-cloth-26.2/first-run-config.json` |

The control is the one 26.2 used: `PathCacheMode implements SelectionListEntry.Translatable`, the
Cloth GUI interface that broke a Cloth-less server the first time. It dies with

```
Caused by: java.lang.NoClassDefFoundError: Could not initialize class dev.pathweaver.config.PathWeaverConfig
Caused by: java.lang.ExceptionInInitializerError: Exception java.lang.NoClassDefFoundError:
    me/shedaniel/clothconfig2/gui/entries/SelectionListEntry$Translatable [in thread "main"]
```

and writes no config file, so row 2 starting means something.
