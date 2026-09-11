#!/usr/bin/env python3
"""Can the provider enumeration refuse? Offline, on classpaths built to be wrong on purpose.

The enumeration is what turns "the loader reported id X" into "only one entry could have supplied
id X". Every receipt that cites it rests on it being able to say NOT ESTABLISHED, and twice it could
not: a jar it failed to open was filed as a harmless loose file, and an id with no provider at all
passed as long as a sibling id sharing its prefix had exactly one.

Neither was visible from a passing run. A real classpath has no corrupt jars and no missing ids, so
the only way to see those paths is to build a classpath that has them. That is what this does: each
case constructs entries on disk, runs the real main(), and requires the exit status the case names.

The accept cases are not decoration. A scanner that refused everything would satisfy every rejection
here and be useless, so the same apparatus has to return 0 on a classpath that is genuinely fine.

    python bench/classpath_providers_control.py
"""

import io
import json
import os
import shutil
import sys
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import classpath_providers  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
# Under build/, like every other scratch area in this repo, and never a JUnit temp directory: those
# failed repeatedly under repeated runs and took a whole suite down with them.
SCRATCH = os.path.join(ROOT, "build", "provider-control")

HARNESS_ID = "pathweaver_gametest"


def manifest(mod_id):
    return json.dumps({"schemaVersion": 1, "id": mod_id, "version": "1.0.0"}).encode("utf-8")


def jar(name, mod_id, nested=()):
    """A jar declaring mod_id, optionally carrying other mods under META-INF/jars like Fabric API."""
    path = os.path.join(SCRATCH, name)
    with zipfile.ZipFile(path, "w") as archive:
        if mod_id is not None:
            archive.writestr("fabric.mod.json", manifest(mod_id))
        for index, inner in enumerate(nested):
            inner_path = os.path.join(SCRATCH, "_inner%d.jar" % index)
            with zipfile.ZipFile(inner_path, "w") as inner_archive:
                inner_archive.writestr("fabric.mod.json", manifest(inner))
            archive.write(inner_path, "META-INF/jars/%s.jar" % inner)
            os.remove(inner_path)
    return path


def corrupt_jar(name):
    """Named .jar, is not a zip. A truncated download and a text file look identical to is_zipfile."""
    path = os.path.join(SCRATCH, name)
    io.open(path, "wb").write(b"PK\x03\x04 truncated before anything readable")
    return path


def loose_file(name):
    path = os.path.join(SCRATCH, name)
    io.open(path, "wb").write(b"a license, a properties file, anything that is not a mod")
    return path


def exploded(name, mod_id):
    path = os.path.join(SCRATCH, name)
    os.makedirs(path, exist_ok=True)
    io.open(os.path.join(path, "fabric.mod.json"), "wb").write(manifest(mod_id))
    return path


def healthy_entries():
    """Enough shape to clear the scan's own preconditions: many mods, some of them nested."""
    return [
        exploded("gametest-resources", HARNESS_ID),
        jar("fabric-api.jar", "fabricloader", nested=("fabric_api_base", "fabric_networking",
                                                      "fabric_registry", "fabric_lifecycle")),
        jar("lithium.jar", "lithium"),
        jar("sodium.jar", "sodium"),
    ]


def classpath_of(entries, name):
    path = os.path.join(SCRATCH, name)
    io.open(path, "w", encoding="utf-8", newline="\n").write("\n".join(entries) + "\n")
    return path


def run(entries, name, mod_id=HARNESS_ID):
    return classpath_providers.main(["classpath_providers", classpath_of(entries, name), mod_id])


def cases():
    yield ("a clean classpath with one provider for the id is accepted", 0,
           lambda: run(healthy_entries(), "clean.txt"))

    yield ("a SECOND entry declaring the same id is refused", 1,
           lambda: run(healthy_entries() + [jar("impostor.jar", HARNESS_ID)], "duplicate.txt"))

    yield ("a jar that cannot be opened is refused, not filed as a loose file", 1,
           lambda: run(healthy_entries() + [corrupt_jar("truncated.jar")], "corrupt.txt"))

    yield ("a corrupt jar NESTED inside a good one is refused", 1,
           lambda: run(healthy_entries() + [nested_corrupt()], "corrupt-nested.txt"))

    # The prefix hole, in the exact shape that used to pass: the reported id has no provider at all,
    # and a sibling sharing its prefix has exactly one.
    yield ("an id with NO provider is refused even when a prefix sibling has one", 1,
           lambda: run([exploded("sibling", HARNESS_ID + "_audited")]
                       + healthy_entries()[1:], "missing-id.txt"))

    yield ("a genuinely absent id is refused", 1,
           lambda: run(healthy_entries(), "absent.txt", mod_id="pathweaver_gametest_nonexistent"))

    # Controls on the scan's own preconditions, which exist so an empty read cannot pass as a clean one.
    yield ("a nearly empty classpath is refused rather than reported duplicate-free", 1,
           lambda: run([exploded("only-one", HARNESS_ID)], "tiny.txt"))

    yield ("a classpath with no nested mods is refused for this dependency set", 1,
           lambda: run([exploded("gametest-resources", HARNESS_ID),
                        jar("a.jar", "mod_a"), jar("b.jar", "mod_b"),
                        jar("c.jar", "mod_c"), jar("d.jar", "mod_d")], "flat.txt"))

    yield ("a classpath entry that does not exist is refused", 1,
           lambda: run(healthy_entries() + [os.path.join(SCRATCH, "was-deleted.jar")], "gone.txt"))

    # The negative control for the corrupt-jar case: the same fall-through path, with a file that
    # really is not a mod. If this refused too, the check above would be proving nothing.
    yield ("a loose non-archive file is still not a problem", 0,
           lambda: run(healthy_entries() + [loose_file("LICENSE.txt")], "loose.txt"))

    yield ("a jar carrying no fabric.mod.json is still not a problem", 0,
           lambda: run(healthy_entries() + [jar("plain-library.jar", None)], "library.txt"))


def nested_corrupt():
    """A readable jar whose META-INF/jars entry is not a readable jar."""
    path = os.path.join(SCRATCH, "carrier.jar")
    with zipfile.ZipFile(path, "w") as archive:
        archive.writestr("fabric.mod.json", manifest("carrier_mod"))
        archive.writestr("META-INF/jars/broken.jar", b"not a zip at all")
    return path


def main():
    if os.path.isdir(SCRATCH):
        shutil.rmtree(SCRATCH)
    os.makedirs(SCRATCH)

    failures = 0
    for name, expected, case in cases():
        print("  ---- %s" % name)
        try:
            got = case()
        except Exception as failure:                                  # noqa: BLE001
            got = "raised %r" % (failure,)
        ok = (got == expected)
        failures += 0 if ok else 1
        print("  %-4s expected exit %s, got %s\n" % ("PASS" if ok else "FAIL", expected, got))

    print("  %s" % ("all control cases behaved as required" if failures == 0
                    else "%d CONTROL CASES FAILED" % failures))
    return 0 if failures == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
