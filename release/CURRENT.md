# What the live Modrinth page currently shows

**body-0.9.0.md**

Published with the 0.9.0 release on 2026-09-14 by `release/publish-0.9.0.py`, and read back from the
public API the same day without credentials: 17 versions listed, `0.9.0+26.1.2` (game versions 26.1.1,
26.1.2, sha1 `2b0131ac...`) and `0.9.0+26.2` (26.2, sha1 `19a8a8f1...`) listed and featured, both 0.8.0
versions no longer featured, the project body 18,896 characters and equal to `body-0.9.0.md`.

The jars are the fresh-checkout builds named in `docs/evidence/RELEASE-JARS-0.9.0.txt` (sha256
`47e24d24...` and `5a77d70a...`). Cloth Config and Mod Menu are listed as optional dependencies, Fabric
API as required.

`body-0.8.1.md` was never published. Its corrections are part of `body-0.9.0.md`, and 0.8.1 was skipped.

A page-only correction goes through `release/update_page.py`; a page published alongside a binary goes
through a release script. Either needs Steven's confirmation.

Update this file in the same commit that changes what is live.
