# What the live Modrinth page currently shows

**body-0.9.0-correction-2.md**. The 0.9.0+26.1.2 and 0.9.0+26.2 versions carry
**changelog-0.9.0-correction-2.md**; 0.9.0+26.3 carries **changelog-0.9.0-26.3.md**.

Published 2026-10-13 by `release/publish-0.9.0-26.3.py` (mc-26.3 branch): the 0.9.0+26.3 version
(game version 26.3, beta, featured, jar sha256 `7905f731...` built in a clean worktree at tag
`v0.9.0-26.3`), the page body, and the two older changelogs, all read back as matching. The served
jar was also downloaded from the CDN and hashed: `7905f731...`. 18 versions listed, the three 0.9.0
builds featured.

The correction: the busy-server table described its zombies as chasing a player, but that run's
test player had been removed a second after joining, so they wandered. It now shows the re-run with
the player present (`docs/evidence/perf-2026-10/` on master): 4 to 12% instead of 5 to 11%, with the
old numbers named in the page's Corrections section.

## Before that

**body-0.9.0-correction-1.md**, with **changelog-0.9.0-correction-1.md** on both 0.9.0 versions.

Corrected 2026-09-14 by `release/correct-0.9.0-page.py`, which read the text back from the public API
and confirmed the summary, status, files, dependencies and featured flags did not change. The
0.9.0 page and changelog said every feature except LOD gives a mob the path it would have had, with
two exceptions; `repathToleranceBlocks` defaults to 1, which is a third. Found by the senior dev's
review of the 0.10 plan. `body-0.9.0.md` and `changelog-0.9.0.md` stay as the record of what was
first published.

The original publication, for the record:

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
