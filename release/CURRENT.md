# What the live Modrinth page currently shows

**body-0.8.0.md**

Last published with the 0.8.0 release. Read back from the Modrinth API on 2026-09-12: both
`0.8.0+26.1.2` and `0.8.0+26.2` carry `date_published` 2026-09-06 and the project's `updated`
field is 2026-09-06, so the 2026-09-07 this file used to state was a day out. 15 versions are
listed, the newest being 0.8.0.

Two later bodies are written and NOT published:

- `body-0.8.1.md` corrects four claims in the live page, two of which are wrong today.
- `body-0.9.0.md` is the same page brought forward: the route-sharing toggle window is now described
  as fixed rather than pending, the status correction is added, and the "never a stale one" tooltip
  is recorded as fixed in the 0.9.0 jar.
  It now also states the measured tick saving with the scenario it came from, says the mod needs
  Fabric API alone, and describes the optional distance-LOD setting and that it ships off, with the one
  LOD figure that exists, a best case from a preregistered test built to favour it. The saving is
  5 to 11%, measured on 2026-09-14 on the jar with the hot-path fixes (sha256 `0ba3d71a...`; the jar that ships,
  `47e24d24...`, differs from it only in one JSON file's line endings), which
  replaced the 6-10% table from an earlier build. The live 0.8.0 page carries no such figure.

Publishing either needs Steven's confirmation. A page-only correction goes through
`release/update_page.py`; a page published alongside a binary goes through the release script.

Update this file in the same commit that changes what is live.
