Mostly defects. Around twenty of them, found by four parallel read-only hunts over the async, mixin, gate and config packages, then by an adversarial review of the whole diff.

## Player-visible

- **Mobs no longer freeze for up to a second after the world changes under them.** Vanilla nulls a mob's path and asks for a new one. Because the answer arrived a tick later, it recorded a recompute that never produced a path, and both of its retry routes then stayed shut for twenty ticks on a mob standing still. Control goes back to vanilla now.
- **A goal can no longer be told a mob is reachable using a route to somewhere it abandoned.** While a search is in flight, `path` and `targetPos` name different destinations, and vanilla's own path-reuse check treats them as a pair. Direct queries got the stale route.
- **`AUDITED` works on 26.1.1**, not only 26.1.2. The audits now gate on the bytes they pinned rather than on a version label. The pinned vanilla classes are byte-identical on 26.1.1, and different on 26.2, so 26.2 still refuses.
- **Workers skip searches nobody wants any more**, instead of computing them and throwing the result away.

## Six ways it could fail open, all live in 0.6.1

- A negative `workerFailureLimit` landed on the documented "never switch anything off" value. That disabled the family-shutdown safety net while the settings screen showed something legal.
- A config written before the tier setting existed inherited today's permissive default, moving an operator from "scan armed" to "waive everything" with no log line.
- The denial set in `SafetyGate` was public and mutable, so any class on the classpath could clear every scan denial, including the ones a failed scan installs that no tier is allowed to waive.
- A mixin config registered after startup was invisible to the scan, because pathfinding classes are not transformed until a world starts. That was the one place where absent evidence produced ALLOW.
- One third-party jar with unusual capitalisation in its manifest could make the mod permanently inert, through a scan failure no tier can waive.
- Setting `trustedMods` to null made the settings screen unopenable, with no message.

## Reporting

`/pathweaver mobs` called a mob eligible when dispatch would have refused it. The status line said breaker-stopped families were "running anyway", contradicting a line three rows above it. Both reporting sites printed the raw denial set size, showing `1` while five of six families were refused. A trimmed path counted as a successful install and cleared the failure cooldown with no route in place. Requests left over from a previous session were divided into this session's dispatch total.

## Testing

375 unit tests, four game-test harnesses, and roughly thirty mutations compiled and observed to fail. Three of those survived their first defence, including two tests of mine that passed for the wrong reason.

This release is correctness work. No performance change was measured for it, and none is claimed.
