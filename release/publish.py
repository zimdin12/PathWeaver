"""Publish a release: verify the tree, tag, upload, update the page, unfeature the previous version.

Run from the repository root: `python release/publish.py`.

Everything here that looks like paranoia was earned. The upload is the only irreversible step in this
project, and the checks below exist because each of them was, at some point, the thing that would have
let a wrong artifact out:

- **A clean tree, asserted.** During the 0.6.1 review a reviewer had a deliberate mutation applied to
  `PathNavigationMixin` while a rebuild happened in the same window. `build/libs/` is just bytes on
  disk; nothing about them says which source produced them. So this refuses to upload unless the tree
  is clean and it can name the commit.
- **Verification that cannot be skipped.** The previous version of this script compared the served
  SHA-1 only *if* a matching version turned up in the listing. If the API paginated, lagged, or
  returned anything unexpected, the loop matched nothing, no comparison ran, and it printed DONE and
  exited 0 — the one check the script exists to perform, silently absent.
- **Every response checked.** The upload raised on failure; the two PATCHes printed a status code and
  carried on.
"""
import hashlib
import json
import os
import subprocess
import sys

import requests

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

VERSION = "0.6.1"
MC = "26.1.2"
TAG = f"v{VERSION}"
PID = "ZQJOU3vB"
JAR = f"build/libs/pathweaver-{VERSION}+{MC}.jar"
TITLE = "0.6.1 — Notice, instead of predicting"

TOK = os.environ["MODRINTH_PAT"]
H = {"Authorization": TOK, "User-Agent": "CasualZ/pathweaver-publish (steven.zimdin@gmail.com)"}


def run(*args):
    return subprocess.run(args, capture_output=True, text=True, check=True).stdout.strip()


def fail(message):
    print("ABORT:", message)
    raise SystemExit(1)


# ---- 1. The bytes must be attributable to a commit -----------------------------------------------
if not os.path.isfile(JAR):
    fail(f"{JAR} does not exist. Build it first, from a clean tree.")
dirty = run("git", "status", "--porcelain")
if dirty:
    fail("the working tree is not clean, so nothing can say which source produced "
         f"{JAR}:\n{dirty}")
commit = run("git", "rev-parse", "HEAD")
local_sha1 = hashlib.sha1(open(JAR, "rb").read()).hexdigest()
print(f"publishing {VERSION} from {commit[:10]}")
print(f"  jar   {JAR}")
print(f"  sha1  {local_sha1}")

# ---- 2. Tag the exact commit whose jar was verified ----------------------------------------------
existing = run("git", "tag", "--list", TAG)
if existing:
    tagged = run("git", "rev-list", "-n", "1", TAG)
    if tagged != commit:
        fail(f"{TAG} already exists and points at {tagged[:10]}, not {commit[:10]}")
    print(f"  tag   {TAG} already on this commit")
else:
    subprocess.run(["git", "tag", "-a", TAG, "-m", TITLE], check=True)
    print(f"  tag   {TAG} created")

notes = open("release/notes.md", encoding="utf-8").read()
body = open("release/body.md", encoding="utf-8").read()

data = {
    "name": TITLE,
    "version_number": f"{VERSION}+{MC}",
    "changelog": notes,
    "dependencies": [
        {"project_id": "P7dR8mSH", "dependency_type": "required"},   # Fabric API
        {"project_id": "9s6osm5g", "dependency_type": "required"},   # Cloth Config
    ],
    "game_versions": [MC],
    "version_type": "beta",
    "loaders": ["fabric"],
    "featured": True,
    "project_id": PID,
    "file_parts": ["file"],
    "primary_file": "file",
}

# ---- 3. Upload ----------------------------------------------------------------------------------
with open(JAR, "rb") as fh:
    r = requests.post("https://api.modrinth.com/v2/version", headers=H,
                      data={"data": json.dumps(data)},
                      files={"file": (os.path.basename(JAR), fh, "application/java-archive")})
print("upload:", r.status_code)
if r.status_code >= 300:
    fail(r.text[:600])

# ---- 4. Page body -------------------------------------------------------------------------------
r = requests.patch(f"https://api.modrinth.com/v2/project/{PID}",
                   headers={**H, "Content-Type": "application/json"},
                   data=json.dumps({"body": body}))
print("page update:", r.status_code)
if r.status_code >= 300:
    fail("the version uploaded but the page did not update: " + r.text[:400])

# ---- 5. Verify what the registry actually serves, and unfeature the previous release --------------
r = requests.get(f"https://api.modrinth.com/v2/project/{PID}/version", headers=H)
if r.status_code >= 300:
    fail("uploaded, but the version list could not be read back to verify it: " + r.text[:400])

verified = False
for v in r.json():
    served = v["files"][0]
    if v["version_number"].startswith(VERSION):
        if served["hashes"]["sha1"] != local_sha1:
            fail(f"the registry is serving different bytes: local {local_sha1}, "
                 f"remote {served['hashes']['sha1']}")
        verified = True
        print(f"  {v['version_number']} {v['version_type']} featured={v['featured']} bytes MATCH")
    elif v["version_number"].startswith("0.6.0") and v["featured"]:
        u = requests.patch(f"https://api.modrinth.com/v2/version/{v['id']}",
                           headers={**H, "Content-Type": "application/json"},
                           data=json.dumps({"featured": False}))
        print("  unfeature 0.6.0:", u.status_code)
        if u.status_code >= 300:
            fail("0.6.0 is still featured alongside the new release: " + u.text[:300])

# The whole point of this script. Never let "we could not check" read as "it checked out".
if not verified:
    fail(f"{VERSION} never appeared in the version list, so the uploaded bytes were NEVER verified. "
         "Check the project page by hand before assuming this release is good.")

print("DONE")
