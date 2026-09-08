Correctness and honesty. Five things that were wrong are fixed, three of them things this page or the mod itself told you incorrectly.

## Route sharing survives a settings change

Switching route sharing or the master switch off and on again could leave a stored route in play across changes made while it was off. PathWeaver stops watching for block changes when either switch is off, but stored routes were only discarded when the server stopped, so a route could be handed out after terrain nobody had been watching moved underneath it.

Both switches now discard what was learned under the previous settings, including searches that were already in flight when you changed them. This was reachable from the settings screen in 0.8.0 and 0.8.1 with no restart, and it is the reason to update if you had turned route sharing on.

## Villager paths at the age limit are no longer thrown away

A finished search that arrived at exactly the configured age limit was accepted by one part of the mod and rejected by another, so the work was done and discarded and the mob ran its own search anyway. At `maxResultAgeTicks=1`, which the settings allow, that was every result except the ones that finished within the same tick they were dispatched.

Default settings were affected far less than that, but the wasted searches were real.

## A villager path from the future

If server time moved backwards, on a world reload or a rollback, a finished path already set aside for
a villager could still be handed to it, answering a question asked before the search was dispatched.
The check that refuses a result from the future ran when the result arrived and not when it was
collected, and those are the same rule at two different moments.

## `/pathweaver status` stops overstating route sharing

The line reporting how many searches route sharing skipped was adding together searches that really did not run and cache hits that saved nothing. Hits recorded while the cache was measuring have no stored route to hand out, so they skip nothing. They are now on their own line, and only the first number is a saving.

The cache capacity shown was the one in your settings file rather than the one in use. Capacity is fixed when the server starts, so if you changed it since, the screen now says the change is waiting for a restart.

**If you decided against `SERVE` on the old number, the honest one is smaller.** If you decided for it, look again.

## The route sharing tooltip was too strong

The settings screen said a reused route "is never a stale one". It now says what actually holds: a route is dropped when a block changes along it, and a change away from the route is not noticed, exactly as vanilla does not notice it either. The old wording is in the 0.8.0 and 0.8.1 jars where no page edit could reach it.

## Settings files are checked properly

`resultCacheMode`, `resultCacheMaxAgeTicks` and `resultCacheMaxEntries` were not type-checked when the config was read, so a hand-edited mistake in one of them was quietly coerced instead of reported. Every persisted setting is now checked, and the check is derived from the settings themselves rather than a list that had already fallen behind three times.

## Compatibility checking

The Lithium audit now pins the vanilla pathfinding class it inspects by hash, like the other audits do. It previously inspected whatever was loaded, on the argument that re-deriving the check each time was stronger than pinning it. It is not: that check reads one class and does not follow what that class calls, so on a changed class it would have reported nothing while proving less than it looked like it proved.

No compatibility outcome changes as a result, on any version. Both downloads verify all seven audits on the Minecraft version they are built for; see the project page for what happens if you install the wrong one.

## New: is pathfinding even your problem?

[A ten-minute check](https://github.com/zimdin12/PathWeaver/blob/master/docs/IS-IT-YOUR-BOTTLENECK.md) you can run before installing this mod, using spark. PathWeaver only helps a server whose tick time is going into mob path searches, and plenty of struggling servers are slow for some other reason. It also says how to read the profile without the two mistakes that are easy to make with it.

## Not in this release

No new performance work and no new benchmark numbers. Nothing here changes what paths your mobs take.
