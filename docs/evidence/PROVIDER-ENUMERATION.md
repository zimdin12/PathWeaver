# The provider enumeration, and two ways it could not say no

`bench/classpath_providers.py` is what turns "the loader reported id X" into "only one entry on the
classpath could have supplied id X". Every harness receipt that cites a provider verdict rests on it.

Two defects were found in it on 2026-09-12, both of which produced a green verdict rather than an
error, which is why neither was visible from a passing run.

## 1. A jar it could not open was filed as a harmless loose file

`zipfile.is_zipfile` returns False for a truncated, corrupt or half-written archive exactly as it
does for a text file. The old code's final branch read:

> Not a jar and not a directory: a loose file. It cannot carry a `fabric.mod.json`, so it is not a
> provider, and it is not a problem either.

So an entry named `something.jar` that could not be opened was counted as nothing to see. That is the
worst shape of wrong zero available here: the entry a competing provider would hide in is precisely
the entry the scan could not read, and it was reported as absence rather than as ignorance.

Now an entry whose name ends `.jar` or `.zip` and does not open is counted as unclassified, and a
single unclassified entry blocks the verdict. A loose file that is not named like an archive is still
not a problem, which is the negative control: without it, this check would refuse everything and
prove nothing.

## 2. A verdict about the wrong id

The scan took an id *prefix*. `pathweaver_gametest` is a prefix of `pathweaver_gametest_audited`,
`pathweaver_gametest_aggregate` and the rest of the family, so asking about one harness id silently
asked about all of them, and the run passed as long as every id in the family had one provider. An id
with **no** provider at all passed too, because `if not matching` only fires when nothing in the
family matches.

This is not hypothetical. Re-running the scan today against the preserved classpath for the `stock`
harness, with the old matcher:

```
  OK   pathweaver_gametest_aggregate  1 provider(s): build/resources/gametest [c61851066e2f]
  VERDICT: single candidate provider for the harness id
```

The question asked was about `pathweaver_gametest`. The verdict returned is about
`pathweaver_gametest_aggregate`. The sentence reads as a clean result and is attached to a different
noun than the one in the question. With the exact-id check, the same input now reports:

```
  FAIL no entry on the classpath declares the id 'pathweaver_gametest' itself; the loader reported
       an id this enumeration cannot account for
  VERDICT: NOT ESTABLISHED
```

## Why that re-run is not a retraction of the recorded series

The re-run above is itself invalid as evidence about the harness series, and for an instructive
reason. `build/resources/gametest` is a build directory that later Gradle invocations rewrite. The
preserved `stock.classpath.txt` is a list of paths; reading those paths today reads today's contents,
not the contents the harness launched against. The digests say so directly: the inventory recorded at
launch carries `fd649f43714e` declaring `pathweaver_gametest`, and the same path today carries
`c61851066e2f` declaring `pathweaver_gametest_aggregate`.

This is why the roster captures `<harness>.providers.txt` as content at launch and why
`bench/harness-receipt.sh` reads that recording rather than re-deriving it. The recorded verdicts for
the 0.9.0 series stand on those content-bound records. Nothing in this document changes them.

It is worth being exact about what the accident demonstrated, though: re-deriving a verdict from live
state produced a *green* answer under the old matcher, not an obviously broken one.

## Control

`bench/classpath_providers_control.py` drives the real `main()` against classpaths built on disk to
be wrong in named ways: a duplicate id, a corrupt jar, a corrupt jar nested inside a good one, an id
with no provider whose prefix sibling has one, an absent id, a near-empty classpath, a classpath with
no nested mods, and a missing entry. Two accept cases keep it honest, since a scanner that refused
everything would satisfy every rejection above.

    python bench/classpath_providers_control.py

11 of 11 at the time of writing.
