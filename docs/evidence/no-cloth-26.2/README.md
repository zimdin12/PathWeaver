# Cloth Config optional on 26.2, verified by booting a server without it

Measured 2026-09-12, on a clean Fabric 26.2 server (loader 0.19.5) built from
nothing: only the mods each row names, a flat world, and no other configuration. Account names and
the scratch directory are replaced with `<USER>`, `<HOME>` and `<SERVER>`; nothing else is edited.

| file | mods | result |
|---|---|---|
| `1-fabricapi-only-STARTS.log` | Fabric API 0.157.0+26.2 | starts, `Done (0.887s)` |
| `2-pathweaver-no-cloth-STARTS.log` | + PathWeaver 0.9.0+26.2, built at `3e6f542` | starts, `PathWeaver is ACTIVE: all 6 movement families can path off-thread`, `Done (0.386s)` |
| `3-CONTROL-cloth-interface-FAILS.log` | + the same jar, one line changed | never starts |
| `first-run-config.json` | | what row 2 wrote on its first run |

Rows 2 and 4 were retaken after the LOD fix changed the shipped interval, so the config here is the one the current jar writes. Row 3 is the control build and row 1 is untouched by either.

Row 3 is the reason the other two mean anything. The equivalent evidence on the 26.1.2 branch has a
genuine before-and-after, because there the failure was found rather than constructed. On this branch
the fix arrived with the port, so there is no naturally broken build to boot, and a pair of green logs
would prove only that a server starts.

So the control puts the fault back on purpose: `PathCacheMode implements
SelectionListEntry.Translatable`, one line, the same Cloth GUI interface that broke it the first time.
That build dies with

```
Caused by: java.lang.NoClassDefFoundError: Could not initialize class dev.pathweaver.config.PathWeaverConfig
Caused by: java.lang.ExceptionInInitializerError: Exception java.lang.NoClassDefFoundError:
    me/shedaniel/clothconfig2/gui/entries/SelectionListEntry$Translatable [in thread "main"]
```

and writes no config file. The probe can say no, so its yes is worth something.

That control also caught a mistake while these were being taken. Row 2 was first run against a jar
built from the mutated source, because the revert had not been rebuilt, and it failed exactly like
row 3. Without a known-bad case to compare against, a green row 2 taken at the wrong moment would have
looked like proof.

The standing cheap version of all this is `NoClothOnTheServerPathTest`, which reads the compiled bytes
of the classes a server loads and refuses a `clothconfig2` reference in any of them, with
`ClothScreen` as its own positive control. It runs on every build; these boots do not.
