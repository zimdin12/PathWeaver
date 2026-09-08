A permission check that was described but never existed. Nothing else changes.

## The fix

`/pathweaver mobs` reports which of your mob types are eligible for off-thread pathing. To do that it builds one of every registered mob type, which on a large pack takes about a fifth of a second, on the server thread.

That subcommand was meant to require operator level. The source comment said it did and the 0.7.0 release notes said it did. It did not: there was no permission check anywhere in the command, so any player on any server could run it as often as they liked.

It now requires the same level as other expensive server commands. `/pathweaver status` stays open to everyone, because that is the one the documentation tells players to run and gating it once locked every singleplayer player out of it.

**If you host a multiplayer server on 0.7.0 or 0.8.0, this is the reason to update.** Singleplayer is unaffected in practice.

## What is not in this release

No new defaults, no performance work, no behaviour changes, no new claims. The version was cut from the exact 0.8.0 release commit with this one change on top, so that updating carries no other risk.

## Also

The project description has been corrected. Two published figures were labelled in ways the measurements did not support, and one guarantee about route sharing was stated more strongly than it holds. The description now says what was wrong rather than quietly restating it, and names one claim that is inside the shipped jar where a page edit cannot reach it.
