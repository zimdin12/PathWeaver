"""Publish 0.9.0: two artifacts, one page update, every claim verified against what is served.

Run from the repository root. Set MODRINTH_PAT in the environment; it is never written here.
Set PATHWEAVER_DRY_RUN=1 to run every check and print exactly what would be sent, uploading nothing.

Each artifact is built in its own detached worktree pinned to the tagged commit, and is refused unless
that worktree is clean, at that commit, and its jar has exactly the sha256 written below. The last
condition is new in 0.9.0. A jar built in the everyday checkout came out different from the same commit
built fresh, because files git stores with LF had CRLF on disk and a resource is packed as it sits on
disk (docs/evidence/RELEASE-JARS-0.9.0.txt). Pinning the hash means a jar that did not come from a clean
checkout cannot be uploaded, whatever the tree looks like at the moment this runs.

Dependencies change in this release: Cloth Config is optional now, not required.
"""
import hashlib
import json
import os
import subprocess
import sys

import requests

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

VERSION = "0.9.0"
PID = "ZQJOU3vB"
TITLE = "0.9.0 - Correctness, Cloth optional, distance LOD"
PREVIOUS = "0.8.0"
DRY = os.environ.get("PATHWEAVER_DRY_RUN") == "1"

# (worktree, expected commit, jar, expected sha256, game versions for this file, label)
ARTIFACTS = [
    ("../pw-090", "013dcf630e96159fb58b62f44d7b133e2016ca57",
     "build/libs/pathweaver-0.9.0+26.1.2.jar",
     "47e24d240003c90e8700b53cb2d8845ef31219d8ad790622ffc61291cf9732c9",
     ["26.1.1", "26.1.2"], "26.1.2"),
    ("../pw-090-262", "c54f373186683b3241fb1761a0e37a5869a25e28",
     "build/libs/pathweaver-0.9.0+26.2.jar",
     "5a77d70a41f681cf4bc131e2e63ea2138e9fab1eff91e17008372d6a3c4614ca",
     ["26.2"], "26.2"),
]

DEPENDENCIES = [
    {"project_id": "P7dR8mSH", "dependency_type": "required"},   # Fabric API
    {"project_id": "9s6osm5g", "dependency_type": "optional"},   # Cloth Config: the settings screen only
    {"project_id": "mOgUt4GM", "dependency_type": "optional"},   # Mod Menu: where that screen is opened
]


def run(*args, cwd=None):
    return subprocess.run(args, capture_output=True, text=True, check=True, cwd=cwd).stdout.strip()


def fail(message):
    print("ABORT:", message)
    raise SystemExit(1)


changelog = open("release/changelog-0.9.0.md", encoding="utf-8").read()
body = open("release/body-0.9.0.md", encoding="utf-8").read()
for name, text in (("changelog", changelog), ("body", body)):
    if any(ord(c) > 127 for c in text):
        fail(f"{name} contains non-ASCII; keep the published text plain so no encoding can mangle it")

# ---- 1. Every artifact must be the exact bytes a clean checkout of its commit produces -----------
staged = []
for tree, expect, jar, expect_sha256, game_versions, label in ARTIFACTS:
    path = os.path.join(tree, jar)
    if not os.path.isfile(path):
        fail(f"{path} does not exist; build it in {tree} first")
    dirty = run("git", "status", "--porcelain", cwd=tree)
    if dirty:
        fail(f"{tree} is not clean, so nothing can say which source produced {jar}:\n{dirty}")
    head = run("git", "rev-parse", "HEAD", cwd=tree)
    if head != expect:
        fail(f"{tree} is at {head[:10]}, expected {expect[:10]}")
    blob = open(path, "rb").read()
    sha256 = hashlib.sha256(blob).hexdigest()
    if sha256 != expect_sha256:
        fail(f"{path} has sha256 {sha256}, expected {expect_sha256}")
    staged.append({
        "label": label, "path": path, "game_versions": game_versions,
        "sha1": hashlib.sha1(blob).hexdigest(),
        "version_number": f"{VERSION}+{label}",
    })
    print(f"  {label:<8} {path}")
    print(f"           commit {head[:10]}  sha256 {sha256[:16]}  sha1 {staged[-1]['sha1']}")

for _label in [a[5] for a in ARTIFACTS]:
    _name = f"{TITLE} ({_label})"
    if len(_name) > 64:
        fail(f"version title is {len(_name)} chars, Modrinth allows 64: {_name}")

# ---- 2. Nothing is uploaded over an existing version, and the previous release is the one featured -
H = {"Authorization": os.environ.get("MODRINTH_PAT", ""),
     "User-Agent": "CasualZ/pathweaver-publish (+https://github.com/zimdin12/PathWeaver)"}
if not DRY and not H["Authorization"]:
    fail("MODRINTH_PAT is not set")
listing_before = requests.get(f"https://api.modrinth.com/v2/project/{PID}/version",
                              headers={"User-Agent": H["User-Agent"]}, params={"cb": os.getpid()})
if listing_before.status_code >= 300:
    fail("could not read the current version list: " + listing_before.text[:300])
existing = {v["version_number"] for v in listing_before.json()}
clash = [a["version_number"] for a in staged if a["version_number"] in existing]
if clash:
    fail(f"already published: {clash}")
featured = sorted(v["version_number"] for v in listing_before.json() if v["featured"])
print(f"\ncurrently listed: {len(existing)} versions; featured: {featured}")

print(f"\npublishing {VERSION} as TWO versions, type=beta, changelog {len(changelog)} chars, "
      f"body {len(body)} chars")
for a in staged:
    print(f"  {a['version_number']:<16} game_versions={a['game_versions']}  title={TITLE} ({a['label']})")
print("  dependencies:", ", ".join(f"{d['project_id']}={d['dependency_type']}" for d in DEPENDENCIES))
print(f"  afterwards: unfeature {PREVIOUS}*, replace the page body, verify served bytes and body")

if DRY:
    print("\nDRY RUN: nothing was uploaded and the page was not touched.")
    raise SystemExit(0)

# ---- 3. Upload each artifact as its own version --------------------------------------------------
for a in staged:
    data = {
        "name": f"{TITLE} ({a['label']})",
        "version_number": a["version_number"],
        "changelog": changelog,
        "dependencies": DEPENDENCIES,
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

# ---- 4. Page body --------------------------------------------------------------------------------
r = requests.patch(f"https://api.modrinth.com/v2/project/{PID}",
                   headers={**H, "Content-Type": "application/json"},
                   data=json.dumps({"body": body}))
print("page update:", r.status_code)
if r.status_code >= 300:
    fail("the versions uploaded but the page did not update: " + r.text[:400])

# ---- 5. Verify what is actually served, then unfeature the previous release ----------------------
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
    deps = sorted((d["project_id"], d["dependency_type"]) for d in match[0]["dependencies"])
    if deps != sorted((d["project_id"], d["dependency_type"]) for d in DEPENDENCIES):
        fail(f"{a['version_number']} is served with dependencies {deps}")
    print(f"  {a['version_number']} bytes and dependencies MATCH")

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
