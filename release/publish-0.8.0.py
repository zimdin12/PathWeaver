"""Publish 0.7.0: two artifacts, one page update, every claim verified against what is served.

Run from the repository root. Set MODRINTH_PAT in the environment; it is never written here.
Set PATHWEAVER_DRY_RUN=1 to run every check and print exactly what would be sent, uploading nothing.

Two artifacts, because 26.2 ships different vanilla bytes and gets its own build. Each is built from
its own worktree pinned to the commit that carries mod_version=0.7.0, and neither is trusted unless
that worktree is clean, because bytes on disk cannot say which source produced them.

The verification at the end is the reason this file exists. An earlier version of the release script
compared the served hash only if a matching version happened to appear in the listing, so a paginated
or lagging response meant no comparison ran at all and the script still printed DONE.
"""
import hashlib
import json
import os
import subprocess
import sys

import requests

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

VERSION = "0.8.0"
PID = "ZQJOU3vB"
TITLE = "0.8.0 - Villagers off the tick, routes worth reusing"
PREVIOUS = "0.7.0"
DRY = os.environ.get("PATHWEAVER_DRY_RUN") == "1"

# (worktree, expected commit, jar, game versions for this file)
# Filled in by release/stage-0.8.0.sh, which rebuilds each jar from a clean checkout and writes the
# commit it came from. The commits are pinned rather than read at run time on purpose: this file is
# the record of what was published, and a script that reads HEAD would happily publish whatever the
# tree happened to be at the moment someone ran it.
ARTIFACTS = [
    ("../PathWeaver", "95c5c0acd064b506bd9233079f5e664d3a8577a3",
     "build/libs/pathweaver-0.8.0+26.1.2.jar", ["26.1.1", "26.1.2"], "26.1.2"),
    ("../pw-262", "00de72aa0534cda750b2cf94d210eec7fe33ce3b",
     "build/libs/pathweaver-0.8.0+26.2.jar", ["26.2"], "26.2"),
]

TOK = os.environ["MODRINTH_PAT"]
H = {"Authorization": TOK,
     "User-Agent": "CasualZ/pathweaver-publish (steven.zimdin@gmail.com)"}


def run(*args, cwd=None):
    return subprocess.run(args, capture_output=True, text=True, check=True, cwd=cwd).stdout.strip()


def fail(message):
    print("ABORT:", message)
    raise SystemExit(1)


changelog = open("release/changelog-0.8.0.md", encoding="utf-8").read()
body = open("release/body-0.8.0.md", encoding="utf-8").read()
for name, text in (("changelog", changelog), ("body", body)):
    if any(ord(c) > 127 for c in text):
        fail(f"{name} contains non-ASCII; keep the published text plain so no encoding can mangle it")

# ---- 1. Every artifact must be attributable to a clean commit ------------------------------------
staged = []
for tree, expect, jar, game_versions, label in ARTIFACTS:
    path = os.path.join(tree, jar)
    if not os.path.isfile(path):
        fail(f"{path} does not exist; build it from {tree} first")
    dirty = run("git", "status", "--porcelain", cwd=tree)
    if dirty:
        fail(f"{tree} is not clean, so nothing can say which source produced {jar}:\n{dirty}")
    head = run("git", "rev-parse", "HEAD", cwd=tree)
    if head != expect:
        fail(f"{tree} is at {head[:10]}, expected {expect[:10]}")
    blob = open(path, "rb").read()
    staged.append({
        "label": label, "path": path, "game_versions": game_versions,
        "sha1": hashlib.sha1(blob).hexdigest(),
        "sha512": hashlib.sha512(blob).hexdigest(),
        "version_number": f"{VERSION}+{label}",
    })
    print(f"  {label:<8} {path}")
    print(f"           commit {head[:10]}  sha1 {staged[-1]['sha1']}")

# Modrinth caps the version title at 64 characters and rejects the upload if it is longer. Check
# it here rather than discovering it mid-run: the first attempt failed on the first of two
# uploads, which is a state where one artifact could be public and the other not.
for _label in [a[4] for a in ARTIFACTS]:
    _name = f"{TITLE} ({_label})"
    if len(_name) > 64:
        fail(f"version title is {len(_name)} chars, Modrinth allows 64: {_name}")

print(f"\npublishing {VERSION} as TWO versions, type=beta, changelog {len(changelog)} chars, "
      f"body {len(body)} chars")
for a in staged:
    print(f"  {a['version_number']:<16} game_versions={a['game_versions']}")

if DRY:
    print("\nDRY RUN: nothing was uploaded and the page was not touched.")
    raise SystemExit(0)

# ---- 2. Upload each artifact as its own version --------------------------------------------------
for a in staged:
    data = {
        "name": f"{TITLE} ({a['label']})",
        "version_number": a["version_number"],
        "changelog": changelog,
        "dependencies": [
            {"project_id": "P7dR8mSH", "dependency_type": "required"},   # Fabric API
            {"project_id": "9s6osm5g", "dependency_type": "required"},   # Cloth Config
        ],
        "game_versions": a["game_versions"],
        "version_type": "beta",
        "loaders": ["fabric"],
        "featured": True,
        "project_id": PID,
        "file_parts": ["file"],
        "primary_file": "file",
    }
    with open(a["path"], "rb") as fh:
        r = requests.post("https://api.modrinth.com/v2/version", headers=H,
                          data={"data": json.dumps(data)},
                          files={"file": (os.path.basename(a["path"]), fh,
                                          "application/java-archive")})
    print(f"upload {a['version_number']}: {r.status_code}")
    if r.status_code >= 300:
        fail(r.text[:600])

# ---- 3. Page body --------------------------------------------------------------------------------
r = requests.patch(f"https://api.modrinth.com/v2/project/{PID}",
                   headers={**H, "Content-Type": "application/json"},
                   data=json.dumps({"body": body}))
print("page update:", r.status_code)
if r.status_code >= 300:
    fail("the versions uploaded but the page did not update: " + r.text[:400])

# ---- 4. Verify what is actually served, then unfeature the previous release ----------------------
r = requests.get(f"https://api.modrinth.com/v2/project/{PID}/version", headers=H)
if r.status_code >= 300:
    fail("uploaded, but the version list could not be read back to verify it: " + r.text[:400])
listing = r.json()

for a in staged:
    match = [v for v in listing if v["version_number"] == a["version_number"]]
    if not match:
        fail(f"{a['version_number']} never appeared in the version list, so its bytes were NEVER "
             "verified. Check the project page by hand before assuming this release is good.")
    served = match[0]["files"][0]["hashes"]["sha1"]
    if served != a["sha1"]:
        fail(f"the registry is serving different bytes for {a['version_number']}: "
             f"local {a['sha1']}, remote {served}")
    print(f"  {a['version_number']} bytes MATCH")

for v in listing:
    if v["version_number"].startswith(PREVIOUS) and v["featured"]:
        u = requests.patch(f"https://api.modrinth.com/v2/version/{v['id']}",
                           headers={**H, "Content-Type": "application/json"},
                           data=json.dumps({"featured": False}))
        print(f"  unfeature {v['version_number']}: {u.status_code}")
        if u.status_code >= 300:
            fail(f"{v['version_number']} is still featured alongside the new release: "
                 + u.text[:300])

check = requests.get(f"https://api.modrinth.com/v2/project/{PID}", headers=H).json()
print("served body length:", len(check["body"]), "chars")
print("BODY MATCHES" if check["body"] == body else "BODY MISMATCH")
print("DONE")
