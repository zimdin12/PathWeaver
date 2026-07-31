# Retained evidence

`pathweaver-on.sparkprofile` / `pathweaver-off.sparkprofile` — spark profiles behind the
"Profiled on a real modpack" table in the project README. A 222-mod pack, 220 mixed mobs retargeted
every 6 ticks, 45 seconds each, `compatibilityTier=UNSAFE`, the only variable between them being the
master switch.

Server-thread time in pathfinding: **12.38% off, 4.92% on.**

Saved locally rather than uploaded. Open them at <https://spark.lucko.me/> via *Load from file*, or
parse them with spark's `SamplerData` protobuf — the call tree is flattened, so each node's children
are `children_refs` indices into the thread's node pool rather than nested objects.
