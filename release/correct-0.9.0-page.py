"""Correct the live 0.9.0 page body and both 0.9.0 version changelogs. Uploads no file.

Run from the repository root with MODRINTH_PAT in the environment. PATHWEAVER_DRY_RUN=1 checks
everything and sends nothing.

The correction: the page and changelog said every feature except LOD gives a mob the path it would
have had, with two exceptions. repathToleranceBlocks defaults to 1, which is a third. Only the body
and the changelogs change; the summary, files, dependencies and featured flags are left alone, and
the script refuses if any of those differ afterwards from what they were before.
"""
import hashlib
import json
import os
import sys

import requests

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

PID = "ZQJOU3vB"
DRY = os.environ.get("PATHWEAVER_DRY_RUN") == "1"
BODY_FILE = "release/body-0.9.0-correction-1.md"
CHANGELOG_FILE = "release/changelog-0.9.0-correction-1.md"
LIVE_BODY_FILE = "release/body-0.9.0.md"
LIVE_CHANGELOG_FILE = "release/changelog-0.9.0.md"
VERSIONS = ["0.9.0+26.1.2", "0.9.0+26.2"]
UA = "CasualZ/pathweaver-publish (+https://github.com/zimdin12/PathWeaver)"


def fail(message):
    print("ABORT:", message)
    raise SystemExit(1)


def read(path):
    text = open(path, encoding="utf-8").read()
    if any(ord(c) > 127 for c in text):
        fail(f"{path} contains non-ASCII")
    return text


body, changelog = read(BODY_FILE), read(CHANGELOG_FILE)
live_body, live_changelog = read(LIVE_BODY_FILE), read(LIVE_CHANGELOG_FILE)
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


project, listing = snapshot()
# The live text must be exactly what we published, or this correction would overwrite someone's edit.
if project["body"] != live_body:
    fail("the live body is not body-0.9.0.md; refusing to overwrite an edit made elsewhere")
ids = {}
for number in VERSIONS:
    if number not in listing:
        fail(f"{number} is not listed")
    if listing[number]["changelog"] != live_changelog:
        fail(f"{number}'s live changelog is not changelog-0.9.0.md")
    ids[number] = listing[number]["id"]


def invariants(p, versions):
    return (p["description"], p["status"],
            tuple(sorted((n, v["featured"], v["status"], v["files"][0]["hashes"]["sha1"],
                          json.dumps(sorted((d["project_id"], d["dependency_type"])
                                            for d in v["dependencies"])))
                         for n, v in versions.items())))


before = invariants(project, listing)
print(f"live body matches {LIVE_BODY_FILE}; both changelogs match {LIVE_CHANGELOG_FILE}")
print(f"new body {len(body)} chars (sha256 {hashlib.sha256(body.encode()).hexdigest()[:16]}), "
      f"new changelog {len(changelog)} chars")
print("will PATCH: project body; changelog of", ", ".join(VERSIONS))

if DRY:
    print("DRY RUN: nothing sent.")
    raise SystemExit(0)

r = requests.patch(f"https://api.modrinth.com/v2/project/{PID}",
                   headers={**H, "Content-Type": "application/json"}, data=json.dumps({"body": body}))
print("body:", r.status_code)
if r.status_code >= 300:
    fail(r.text[:400])
for number, vid in ids.items():
    r = requests.patch(f"https://api.modrinth.com/v2/version/{vid}",
                       headers={**H, "Content-Type": "application/json"},
                       data=json.dumps({"changelog": changelog}))
    print(f"changelog {number}:", r.status_code)
    if r.status_code >= 300:
        fail(r.text[:400])

project, listing = snapshot()
ok = project["body"] == body
print("BODY MATCHES" if ok else "BODY MISMATCH (the public API may be cached; re-read before acting)")
for number in VERSIONS:
    same = listing[number]["changelog"] == changelog
    ok = ok and same
    print(f"{number} CHANGELOG", "MATCHES" if same else "MISMATCH")
if invariants(project, listing) != before:
    fail("something other than body and changelog changed: summary, status, files, dependencies or featured")
print("invariants unchanged: summary, status, files, dependencies, featured")
print("DONE" if ok else "NOT VERIFIED")
