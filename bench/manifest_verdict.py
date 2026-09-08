#!/usr/bin/env python3
"""Did this harness run the manifest it was supposed to?

Extracted from the roster loop so it can be exercised on its own with crafted inputs. A classifier
that only ever runs inside the thing it is classifying has never been shown to be able to say no.

    python bench/manifest_verdict.py <consumed.json> <expected.json> [loaded-mod-id]

Prints OK or SETUP-INVALID with a reason, and exits 0 or 1.

WHAT IT COMPARES, and why not bytes
-----------------------------------
The fields that decide what actually runs: the mod id, the entrypoints, and the mixin configs. Not
the whole file, because one harness manifest is GENERATED rather than copied and carries an expanded
version string, so a byte comparison would call the correct file wrong. Not the id alone, because two
harnesses deliberately share an id (breaker reuses the stock one) and an id comparison would call a
swapped file right.

The optional third argument is the mod id Fabric reported loading. When given it must match the
consumed manifest's id, which ties the file on disk to the process that ran. Two harnesses share an
id, so that check can confirm a file was loaded and cannot by itself tell those two apart.
"""

import json
import sys


DECIDING_FIELDS = ("id", "entrypoints", "mixins", "depends")


def read(path):
    with open(path, encoding="utf-8") as handle:
        return json.load(handle)


def verdict(consumed_path, expected_path, loaded_id=None):
    try:
        consumed = read(consumed_path)
    except Exception as failure:
        return False, "consumed manifest unreadable: %s" % failure
    try:
        expected = read(expected_path)
    except Exception as failure:
        return False, "expected manifest unreadable: %s" % failure

    for field in DECIDING_FIELDS:
        if consumed.get(field) != expected.get(field):
            return False, "%s differs: ran %r, expected %r" % (
                field, consumed.get(field), expected.get(field))

    if loaded_id is not None and loaded_id != consumed.get("id"):
        return False, "the loader reported %r but the manifest on disk declares %r" % (
            loaded_id, consumed.get("id"))

    return True, "id=%s entrypoints and mixins match%s" % (
        consumed.get("id"), "" if loaded_id is None else "; loader agrees")


def main(argv):
    if len(argv) < 3:
        print(__doc__)
        return 2
    ok, reason = verdict(argv[1], argv[2], argv[3] if len(argv) > 3 else None)
    print(("OK  " if ok else "SETUP-INVALID  ") + reason)
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))
