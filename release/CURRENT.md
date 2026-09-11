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

Publishing either needs Steven's confirmation. A page-only correction goes through
`release/update_page.py`; a page published alongside a binary goes through the release script.

Update this file in the same commit that changes what is live.
