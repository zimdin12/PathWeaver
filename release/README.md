# Release text lives here, versioned

Modrinth holds one project description and one changelog per version. The description is a live
field: editing it changes what every visitor sees, including visitors looking at an old version. So
it is kept here, per version, and the published page is a copy of one of these files rather than the
only record of what it said.

## Files

| File | What it is |
|---|---|
| `body-<version>.md` | The project description as it should read while `<version>` is the current release. This is the whole page, not a diff. |
| `changelog-<version>.md` | The per-version changelog attached to that version's files on Modrinth. |
| `publish-<version>.py` | The script that published it, with the artifact commits pinned. |

`CURRENT.md` names which `body-*.md` matches the live page right now. Update it in the same commit
that publishes a page edit, or it is a lie the moment someone trusts it.

## Rules

**Never edit a shipped `body-*.md` to make a past claim look better.** If a published figure turns
out to be wrong, the correction goes in the NEW version's body, visibly, naming what was wrong. The
old file stays as the record of what was actually claimed at the time. Rewriting it destroys the
evidence that the mistake happened, which is the only thing that makes the correction trustworthy.

**A page edit cannot reach a shipped jar.** Tooltips, log lines and command output live in the
artifact. When a page correction covers a claim that is also inside released binaries, the new body
must say which versions still carry the wrong text and what fixes it.

**Check every number against its source before copying it forward.** Bodies are largely inherited
from the previous version, which is exactly how a stale figure survives four releases. A number with
no live source is either re-derived or removed.

**Keep the units honest.** The benchmarks here sample thread stacks; they do not measure CPU time.
Say "sampled" where that is what happened, and do not narrow a broad pathfinding measurement into an
"A\*" claim it does not support.

## Publishing

`python release/publish-<version>.py` with `MODRINTH_PAT` in the environment, from the repository
root. It refuses to run against a dirty tree, pins each artifact to a named commit, verifies the
served SHA-1 after upload, and reads the served body back to confirm it matches the file.

To correct only the page, without a binary release, use `release/update_page.py`.

Steven confirms every publication. Nothing here publishes itself.
