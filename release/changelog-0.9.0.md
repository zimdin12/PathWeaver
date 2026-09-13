Correctness and honesty, plus two changes you will notice: Cloth Config is no longer required, and there is a new optional setting for distant mobs.

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

## Cloth Config is no longer required

PathWeaver needed Fabric API and Cloth Config. Cloth Config draws the settings screen, which a
dedicated server never shows, so every server owner was installing a GUI library to run a server-side
mod.

It now needs Fabric API and nothing else. Install Cloth Config if you want the settings screen; without
it the mod reads and writes the same `config/pathweaver.json` and behaves identically. Nothing about
your existing config file changes and no setting is lost.

## New, and off by default: redo distant path searches less often

When a block changes on the route a mob is walking, the game redoes that mob's whole path search. It
will not do it more often than once a second for any one mob, but in a place where terrain keeps
changing, every mob whose route crosses the change pays for it, whether anyone is near enough to see
it or not.

`lodEnabled` widens that limit for mobs beyond 64 blocks from every player: one search every 40 ticks
instead of the game's once a second. Both numbers are settings. A mob picking a new destination is
never affected; this only delays redoing a route the mob already has.

**It ships off, and that is deliberate.** Everything else in this mod gives a mob the path it would
have had anyway. This lets a distant mob keep walking an out-of-date route for longer after the ground
under it changes. That is bounded and remote, but it is a real difference, so it is offered rather than
taken.

Worth turning on for mob farms, penned herds near redstone, or a high simulation distance. It does
nothing at all for mobs whose surroundings are not changing, because the game never redoes their
search in the first place.

## Not in this release

No change to the mod's core numbers. This exact jar was put back through the benchmark against 0.8.0
and against no mod: it is within 3.5% of 0.8.0 and still saves 7 to 10% of tick time on that test.

For LOD there is only a best case. In a test built to favour it, 400 zombies at least 70 blocks from the
player with terrain changing among them all the time, turning it on cut the path searches PathWeaver
started by 13 to 19% and did not measurably change tick time. What it saves on your server depends on
how much terrain changes around mobs nobody is standing near, and in a world where it does not change,
it saves nothing.
