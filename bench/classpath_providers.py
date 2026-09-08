#!/usr/bin/env python3
"""Who else on the harness classpath could have supplied the mod the loader reported?

A receipt that says "the file at build/resources/gametest declares id X and the loader reported
loading id X" shows two things agree. It does not show the loader read THAT file: another classpath
entry declaring the same id would produce the same log line. This closes that gap the only way it can
be closed from outside the loader, by enumerating every provider and showing there is exactly one.

    python bench/classpath_providers.py <classpath-file> [id-prefix]

<classpath-file> holds one classpath entry per line, as the build reports it. Each entry, jar or
directory, is searched for a fabric.mod.json, and every mod id found is attributed to its provider.

Exit 0 when every id matching the prefix has exactly one provider; 1 otherwise. A prefix with no
providers at all is also a failure: an enumeration that found nothing proves nothing.
"""

import json
import os
import sys
import zipfile
from collections import defaultdict


def ids_in(entry):
    """Every (mod id, description of where it came from) this classpath entry provides."""
    found = []
    if os.path.isdir(entry):
        manifest = os.path.join(entry, "fabric.mod.json")
        if os.path.isfile(manifest):
            try:
                with open(manifest, encoding="utf-8") as handle:
                    found.append((json.load(handle).get("id"), entry))
            except Exception as failure:
                found.append(("<unreadable: %s>" % failure, entry))
    elif zipfile.is_zipfile(entry):
        try:
            with zipfile.ZipFile(entry) as archive:
                if "fabric.mod.json" in archive.namelist():
                    raw = archive.read("fabric.mod.json").decode("utf-8", "replace")
                    found.append((json.loads(raw).get("id"), entry))
        except Exception as failure:
            found.append(("<unreadable: %s>" % failure, entry))
    return found


def main(argv):
    if len(argv) < 2:
        print(__doc__)
        return 2
    prefix = argv[2] if len(argv) > 2 else "pathweaver_gametest"

    entries = [line.strip() for line in open(argv[1], encoding="utf-8") if line.strip()]
    providers = defaultdict(list)
    for entry in entries:
        for mod_id, where in ids_in(entry):
            providers[mod_id].append(where)

    print("  classpath entries scanned: %d" % len(entries))
    print("  entries providing a fabric.mod.json: %d" % sum(len(v) for v in providers.values()))
    # The control: this must find the ordinary mods too, or it is not reading the classpath at all.
    print("  distinct mod ids found: %d" % len(providers))
    if len(providers) < 5:
        print("  FAIL the scan found almost no mods; it is not reading the real classpath")
        return 1

    matching = {k: v for k, v in providers.items() if k and k.startswith(prefix)}
    if not matching:
        print("  FAIL no provider declares an id starting %r; nothing was enumerated" % prefix)
        return 1

    failures = 0
    for mod_id, where in sorted(matching.items()):
        unique = len(where) == 1
        failures += 0 if unique else 1
        print("  %-4s %-40s %d provider(s): %s"
              % ("OK" if unique else "FAIL", mod_id, len(where),
                 "; ".join(os.path.relpath(w) if os.path.exists(w) else w for w in where)))
    return 0 if failures == 0 else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))
