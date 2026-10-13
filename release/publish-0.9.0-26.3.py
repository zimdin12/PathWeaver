"""Publish the 26.3 build of 0.9.0, and correct the page and the two 0.9.0 changelogs. One upload.

Run from the repository root with MODRINTH_PAT in the environment; it is never written here.
PATHWEAVER_DRY_RUN=1 runs every check and sends nothing.

The page correction: the busy-server table described its zombies as chasing a player, but the test
player had been removed a second after joining, so they were wandering. The table is replaced by a
re-run with the player present (docs/evidence/perf-2026-10/), and the two 0.9.0 changelogs get the same
correction to their one sentence quoting those numbers.

The script refuses unless the live page and changelogs are exactly what we last published, so it can
never overwrite an edit made elsewhere, and it checks afterwards that nothing it did not mean to change
(summary, status, the existing versions' files, dependencies and featured flags) changed.
"""
import hashlib
import json
import os
import subprocess
import sys

import requests

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

PID = "ZQJOU3vB"
DRY = os.environ.get("PATHWEAVER_DRY_RUN") == "1"
UA = "CasualZ/pathweaver-publish (+https://github.com/zimdin12/PathWeaver)"
TITLE = "0.9.0 - Correctness, Cloth optional, distance LOD (26.3)"
NUMBER = "0.9.0+26.3"
GAME_VERSIONS = ["26.3"]

# The jar is built in its own detached worktree at this commit, and must have exactly this sha256.
TREE = "../pw-090-263"
COMMIT = "25b4c366a89b8ff9c9882b20aeed3dcbbe220595"
JAR = "build/libs/pathweaver-0.9.0+26.3.jar"
SHA256 = "7905f731958b6f925389f840f5574c4990cff04cb6337207dd0464e58e55cc63"

BODY_FILE = "release/body-0.9.0-correction-2.md"
LIVE_BODY_FILE = "release/body-0.9.0-correction-1.md"
CHANGELOG_FILE = "release/changelog-0.9.0-correction-2.md"
LIVE_CHANGELOG_FILE = "release/changelog-0.9.0-correction-1.md"
NEW_VERSION_CHANGELOG_FILE = "release/changelog-0.9.0-26.3.md"
EXISTING = ["0.9.0+26.1.2", "0.9.0+26.2"]

DEPENDENCIES = [
    {"project_id": "P7dR8mSH", "dependency_type": "required"},   # Fabric API
    {"project_id": "9s6osm5g", "dependency_type": "optional"},   # Cloth Config: the settings screen only
    {"project_id": "mOgUt4GM", "dependency_type": "optional"},   # Mod Menu: where that screen is opened
]


def fail(message):
    print("ABORT:", message)
    raise SystemExit(1)


def read(path):
    text = open(path, encoding="utf-8").read()
    if any(ord(c) > 127 for c in text):
        fail(f"{path} contains non-ASCII; keep the published text plain so no encoding can mangle it")
    return text


def git(*args):
    return subprocess.run(["git", *args], capture_output=True, text=True, check=True, cwd=TREE).stdout.strip()


body, live_body = read(BODY_FILE), read(LIVE_BODY_FILE)
changelog, live_changelog = read(CHANGELOG_FILE), read(LIVE_CHANGELOG_FILE)
new_changelog = read(NEW_VERSION_CHANGELOG_FILE)
if len(TITLE) > 64:
    fail(f"version title is {len(TITLE)} chars, Modrinth allows 64")

# ---- 1. The artifact is the exact bytes a clean checkout of its commit produces ------------------
path = os.path.join(TREE, JAR)
if not os.path.isfile(path):
    fail(f"{path} does not exist; build it in {TREE} first")
if git("status", "--porcelain"):
    fail(f"{TREE} is not clean")
head = git("rev-parse", "HEAD")
if head != COMMIT:
    fail(f"{TREE} is at {head}, expected {COMMIT}")
blob = open(path, "rb").read()
if hashlib.sha256(blob).hexdigest() != SHA256:
    fail(f"{path} is not the pinned jar")
sha1 = hashlib.sha1(blob).hexdigest()
print(f"artifact {NUMBER}: {path}\n  commit {head[:10]}  sha256 {SHA256[:16]}  sha1 {sha1}")

# ---- 2. The live page is what we last published, and the version is new ---------------------------
H = {"Authorization": os.environ.get("MODRINTH_PAT", ""), "User-Agent": UA}
if not DRY and not H["Authorization"]:
    fail("MODRINTH_PAT is not set")


def snapshot():
    project = requests.get(f"https://api.modrinth.com/v2/project/{PID}", headers={"User-Agent": UA},
                           params={"cb": os.urandom(4).hex()})
    versions = requests.get(f"https://api.modrinth.com/v2/project/{PID}/version",
                            headers={"User-Agent": UA}, params={"cb": os.urandom(4).hex()})
    if project.status_code >= 300 or versions.status_code >= 300:
        fail("could not read the project back")
    return project.json(), {v["version_number"]: v for v in versions.json()}


def invariants(p, versions):
    return (p["description"], p["status"],
            tuple(sorted((n, v["featured"], v["status"], v["files"][0]["hashes"]["sha1"],
                          json.dumps(sorted((d["project_id"], d["dependency_type"])
                                            for d in v["dependencies"])))
                         for n, v in versions.items() if n != NUMBER)))


project, listing = snapshot()
if project["body"] != live_body:
    fail(f"the live body is not {LIVE_BODY_FILE}; refusing to overwrite an edit made elsewhere")
if NUMBER in listing:
    fail(f"{NUMBER} is already published")
ids = {}
for number in EXISTING:
    if number not in listing:
        fail(f"{number} is not listed")
    if listing[number]["changelog"] != live_changelog:
        fail(f"{number}'s live changelog is not {LIVE_CHANGELOG_FILE}")
    ids[number] = listing[number]["id"]
before = invariants(project, listing)
print(f"live body and both 0.9.0 changelogs match what was last published; {len(listing)} versions listed")
print(f"will: upload {NUMBER} ({GAME_VERSIONS}, beta, featured, changelog {len(new_changelog)} chars);"
      f" replace the body ({len(body)} chars); replace the changelog of {', '.join(EXISTING)}")

if DRY:
    print("DRY RUN: nothing sent.")
    raise SystemExit(0)

# ---- 3. Upload, then the page, then the two changelogs --------------------------------------------
data = {
    "name": TITLE, "version_number": NUMBER, "changelog": new_changelog,
    "dependencies": DEPENDENCIES, "game_versions": GAME_VERSIONS, "version_type": "beta",
    "loaders": ["fabric"], "featured": True, "project_id": PID,
    "file_parts": ["file"], "primary_file": "file",
}
with open(path, "rb") as fh:
    r = requests.post("https://api.modrinth.com/v2/version", headers=H, data={"data": json.dumps(data)},
                      files={"file": (os.path.basename(path), fh, "application/java-archive")})
print(f"upload {NUMBER}:", r.status_code)
if r.status_code >= 300:
    fail(r.text[:600])

r = requests.patch(f"https://api.modrinth.com/v2/project/{PID}",
                   headers={**H, "Content-Type": "application/json"}, data=json.dumps({"body": body}))
print("body:", r.status_code)
if r.status_code >= 300:
    fail("uploaded, but the page did not update: " + r.text[:400])
for number, vid in ids.items():
    r = requests.patch(f"https://api.modrinth.com/v2/version/{vid}",
                       headers={**H, "Content-Type": "application/json"},
                       data=json.dumps({"changelog": changelog}))
    print(f"changelog {number}:", r.status_code)
    if r.status_code >= 300:
        fail(r.text[:400])

# ---- 4. Verify what is served ---------------------------------------------------------------------
project, listing = snapshot()
ok = True
if NUMBER not in listing:
    fail(f"{NUMBER} never appeared in the version list, so its bytes were NEVER verified")
v = listing[NUMBER]
served = v["files"][0]["hashes"]["sha1"]
deps = sorted((d["project_id"], d["dependency_type"]) for d in v["dependencies"])
for label, good in (("bytes", served == sha1),
                    ("dependencies", deps == sorted((d["project_id"], d["dependency_type"]) for d in DEPENDENCIES)),
                    ("game versions", v["game_versions"] == GAME_VERSIONS),
                    ("featured", v["featured"]),
                    ("changelog", v["changelog"] == new_changelog)):
    print(f"{NUMBER} {label}:", "MATCHES" if good else "MISMATCH")
    ok = ok and good
same = project["body"] == body
print("BODY MATCHES" if same else "BODY MISMATCH (the public API may be cached; re-read before acting)")
ok = ok and same
for number in EXISTING:
    same = listing[number]["changelog"] == changelog
    print(f"{number} CHANGELOG", "MATCHES" if same else "MISMATCH")
    ok = ok and same
if invariants(project, listing) != before:
    fail("something else changed: summary, status, or an existing version's files, dependencies or featured flag")
print("invariants unchanged: summary, status, existing files, dependencies, featured")
print("DONE" if ok else "NOT VERIFIED")
