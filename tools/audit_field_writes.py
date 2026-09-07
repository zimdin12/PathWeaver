"""Where does each method of a class write fields?

The Lithium and Diagonal Blocks exemptions rest on a claim about exactly this: that every field write
lives in a constructor, a static initializer, or one named lazy-init method, so a worker running a
search cannot mutate shared state. Re-pinning an audit to a new artifact is not renumbering a hash.
It is re-proving that claim against the new bytecode, and this is the tool that does it.

Two parsing traps cost an hour the first time, and both produced FALSE VIOLATIONS, which is the
direction that wastes time rather than the direction that ships a bad audit:

  - javap names a constructor after the class, not `<init>`, so every constructor write looked like
    an unexpected one.
  - javap prints a static initializer as `static {};`, which did not match the member pattern, so its
    writes were attributed to whichever method happened to be printed above it.

So the member parser is checked in both directions before any verdict is believed: it must find
writes in a class known to have them, and must find none in a class known to have none.

Usage: python tools/audit_field_writes.py <jar> <class/with/slashes> [more classes...]
"""
import re
import subprocess
import sys
import zipfile
from pathlib import Path

JAVAP = r"C:\Program Files\Eclipse Adoptium\jdk-25.0.3.9-hotspot\bin\javap.exe"
MEMBER = re.compile(r"^  (?!\s)(.*?)\s*;\s*$")
STATIC_INIT = re.compile(r"^  static \{\};\s*$")
NAME = re.compile(r"([\w$]+|<init>|<clinit>)\s*\(")


def writes_by_method(class_file, simple_name):
    """Map method name to the fields it writes. Constructors report as <init>."""
    dumped = subprocess.run([JAVAP, "-p", "-c", str(class_file)],
                            capture_output=True, text=True).stdout
    current, found = None, {}
    for line in dumped.splitlines():
        if STATIC_INIT.match(line):
            current = "<clinit>"
            found.setdefault(current, [])
            continue
        member = MEMBER.match(line)
        if member and "(" in member.group(1):
            name = NAME.search(member.group(1))
            current = name.group(1) if name else member.group(1)
            # javap prints `Foo();` for Foo's constructor, and `pkg.Foo();` for a nested one.
            if current == simple_name or current.endswith("." + simple_name):
                current = "<init>"
            found.setdefault(current, [])
        elif ("putfield" in line or "putstatic" in line) and current is not None:
            field = re.search(r"// Field (\S+)", line)
            found[current].append(field.group(1) if field else "?")
    return {m: sorted(set(f)) for m, f in found.items() if f}


def extract(jar, entries, into):
    into.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(jar) as z:
        for e in entries:
            z.extract(e, into)
    return into


def check(jar, claims, workdir):
    """claims: {class path without .class: allowed writer names, or None for 'report only'}"""
    root = extract(jar, [c + ".class" for c in claims], Path(workdir))
    violations = 0
    for cls, allowed in claims.items():
        simple = cls.rsplit("/", 1)[-1]
        writes = writes_by_method(root / (cls + ".class"), simple)
        if allowed is None:
            print(f"  report    {simple:<32} writes in: {sorted(writes) or 'nothing'}")
            continue
        bad = {m: f for m, f in writes.items() if m not in allowed}
        violations += len(bad)
        verdict = "HOLDS" if not bad else "VIOLATED"
        print(f"  {verdict:<9} {simple:<32} writes in: {sorted(writes) or 'nothing'}")
        for method, fields in bad.items():
            print(f"        UNEXPECTED WRITE in {method}: {fields}")
    return violations


if __name__ == "__main__":
    jar = sys.argv[1]
    targets = {c: None for c in sys.argv[2:]}
    sys.exit(1 if check(jar, targets, "build/audit-scratch") else 0)
