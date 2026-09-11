#!/usr/bin/env python3
"""Who else on the harness classpath could have supplied the mod the loader reported?

A receipt saying "the file at build/resources/gametest declares id X and the loader reported loading
id X" shows two things agree. It does not show the loader read THAT file: another entry declaring the
same id would produce the same log line. This closes that gap the only way it can be closed from
outside the loader, by enumerating every provider and showing there is exactly one.

    python bench/classpath_providers.py <classpath-file> [id-prefix]

<classpath-file> holds one classpath entry per line, as the build reports it, captured BEFORE the
launch it is meant to describe. A later enumeration of a mutable build directory cannot establish
what an earlier process could load.

DISCOVERY SCOPE, because a scan that looks in the wrong places reports no duplicates for the wrong
reason. Fabric mods come from a jar's own fabric.mod.json AND from jars nested inside it under
META-INF/jars, which is how the Fabric API ships its modules. Nesting is followed recursively. A
directory entry is read as an exploded mod.

INCOMPLETENESS IS NOT SUCCESS. Any entry this cannot classify is counted and reported, and a single
one blocks the uniqueness claim: an unreadable entry is exactly where a competing provider would
hide. The same applies to finding implausibly few mods, or none matching the prefix.

Exit 0 only when the id itself is declared by something on the classpath, every id in its
family has exactly one provider, and nothing was left unclassified.
"""

import hashlib
import io
import json
import os
import sys
import zipfile
from collections import defaultdict


def read_manifest(raw, where, providers, problems):
    # The digest is the point of recording this at all: a path is not a content binding, and one of
    # these providers is a build directory the next harness rewrites. An inventory that carries the
    # bytes it saw can be read back later without re-opening anything.
    digest = hashlib.sha256(raw).hexdigest()[:12]
    try:
        providers[json.loads(raw.decode("utf-8", "replace")).get("id")].append(
            "%s [%s]" % (where, digest))
    except Exception as failure:
        problems.append("%s: unparsable fabric.mod.json (%s)" % (where, failure))


def scan_archive(handle, where, providers, problems, depth=0):
    """A jar: its own manifest, plus every mod nested under META-INF/jars, recursively."""
    if depth > 4:
        problems.append("%s: nesting deeper than 4, not followed" % where)
        return
    try:
        with zipfile.ZipFile(handle) as archive:
            names = archive.namelist()
            if "fabric.mod.json" in names:
                read_manifest(archive.read("fabric.mod.json"), where, providers, problems)
            for nested in names:
                if nested.startswith("META-INF/jars/") and nested.endswith(".jar"):
                    try:
                        scan_archive(io.BytesIO(archive.read(nested)),
                                     "%s!%s" % (where, nested), providers, problems, depth + 1)
                    except Exception as failure:
                        problems.append("%s!%s: unreadable nested jar (%s)"
                                        % (where, nested, failure))
    except Exception as failure:
        problems.append("%s: unreadable archive (%s)" % (where, failure))


def scan(entry, providers, problems):
    if os.path.isdir(entry):
        manifest = os.path.join(entry, "fabric.mod.json")
        if os.path.isfile(manifest):
            try:
                with open(manifest, "rb") as handle:
                    read_manifest(handle.read(), entry, providers, problems)
            except Exception as failure:
                problems.append("%s: unreadable manifest (%s)" % (entry, failure))
        return
    if not os.path.exists(entry):
        problems.append("%s: classpath entry does not exist" % entry)
        return
    if zipfile.is_zipfile(entry):
        scan_archive(entry, entry, providers, problems)
        return
    # A .jar that is not a readable zip is the one case that must not fall through here. is_zipfile
    # returns False for a truncated, corrupt or half-written archive exactly as it does for a text
    # file, so the old code filed both as "a loose file, not a provider, not a problem". That is a
    # wrong zero of the worst kind: the entry the loader may well have read is the entry this could
    # not open, and it was reported as nothing to see.
    if os.path.splitext(entry)[1].lower() in (".jar", ".zip"):
        problems.append("%s: named like an archive but not readable as one; it cannot be ruled out "
                        "as a provider" % entry)
        return
    # Not an archive, not a directory, not named like an archive: a loose file. It cannot carry a
    # fabric.mod.json, so it is not a provider and not a problem either.


def main(argv):
    if len(argv) < 2:
        print(__doc__)
        return 2
    prefix = argv[2] if len(argv) > 2 else "pathweaver_gametest"

    entries = [line.strip() for line in open(argv[1], encoding="utf-8") if line.strip()]
    providers = defaultdict(list)
    problems = []
    for entry in entries:
        scan(entry, providers, problems)

    nested = sum(1 for v in providers.values() for w in v if "!" in w)
    print("  classpath entries scanned : %d" % len(entries))
    print("  mods found                : %d (%d of them nested inside another jar)"
          % (sum(len(v) for v in providers.values()), nested))
    print("  distinct mod ids          : %d" % len(providers))
    print("  entries not classified    : %d" % len(problems))
    for problem in problems[:10]:
        print("      %s" % problem)

    ok = True
    # Controls on the scan itself. A scan that found nothing reports no duplicates.
    if len(providers) < 5:
        print("  FAIL the scan found almost no mods; it is not reading the real classpath")
        ok = False
    if nested == 0:
        # A precondition of THIS harness dependency set, which is known to include the Fabric API and
        # therefore known to contain nested mods. It is not a general definition of correct discovery:
        # finding some nested mods does not establish that every source a loader consults was covered.
        print("  FAIL no nested mods found; this dependency set ships the Fabric API as nested jars, "
              "so the population is incomplete")
        ok = False
    if problems:
        print("  FAIL %d entr(y/ies) could not be classified; an unreadable entry is exactly where a "
              "competing provider would hide" % len(problems))
        ok = False

    # THE ID THE LOADER REPORTED, not merely something that looks like it. The family scan below is
    # kept because a sibling id with two providers is worth knowing, but it cannot stand in for this:
    # with prefix matching alone, an id with NO provider at all passed as long as one of its siblings
    # had exactly one, and the id with no provider is precisely the one whose selection is unexplained.
    if prefix not in providers:
        print("  FAIL no entry on the classpath declares the id %r itself; the loader reported an id "
              "this enumeration cannot account for" % prefix)
        ok = False

    matching = {k: v for k, v in providers.items() if k and k.startswith(prefix)}
    if not matching:
        print("  FAIL no provider declares an id starting %r; nothing was enumerated" % prefix)
        ok = False

    for mod_id, where in sorted(matching.items()):
        unique = len(where) == 1
        ok = ok and unique
        print("  %-4s %-40s %d provider(s): %s"
              % ("OK" if unique else "FAIL", mod_id, len(where), "; ".join(where)))

    duplicates = {k: v for k, v in providers.items() if len(v) > 1}
    print("  ids provided by more than one entry: %d%s"
          % (len(duplicates), "" if not duplicates else " -> " + ", ".join(sorted(duplicates))))

    # Candidate providers, not the provider Fabric selected. This says how many entries COULD have
    # supplied that id; where the answer is one, no selection question arises for it.
    print("  VERDICT: %s" % ("single candidate provider for the harness id" if ok
                             else "NOT ESTABLISHED"))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))
