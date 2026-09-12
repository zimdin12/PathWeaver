# NeoForge: measured, and deliberately not done in 0.9.0

Decided 2026-09-12. Fabric stays the only loader. This records what was measured before deciding,
so the decision can be re-opened on facts rather than re-argued from memory.

NeoForge was on the 0.9.0 list for one honest reason: the competitor ships it and we do not, and
that is a distribution gap rather than a technical opinion. What follows is why it is still the
right call to leave it.

## It is not blocked on availability

NeoForge publishes builds for both of our targets. The NeoForged maven lists `26.1.2.109` and
`26.2.0.87`, so there is a loader to build against on each branch. Nothing here is waiting on
upstream.

## What a port would actually cost

**The easy part, and it is genuinely easy.** 13 of 65 files under `src/main/java` import
`net.fabricmc`, and most of that is `FabricLoader.isModLoaded`, the lifecycle events and the command
registration callback. Every one has a NeoForge equivalent. On its own this is mechanical work.

**The part that is not a port.** The compatibility gate is a reader of Fabric metadata, by
construction. `ForeignMixinScanner` is 1107 lines whose job is to open `fabric.mod.json` inside every
loaded mod and find foreign mixins aimed at the pathfinding classes; it skips any mod whose metadata
type is not `fabric`. Two of the seven audits pin Fabric API modules by id and hash,
`fabric-content-registries-v0` and `fabric-events-interaction-v0`, which do not exist on NeoForge in
any form. A NeoForge build does not port that gate. It needs a second one, derived from NeoForge's
own mod metadata and its own set of pathfinding-touching mods, and every audit in it would have to be
re-derived from bytes we have never read.

**The evidence base moves with it.** Every number this project publishes comes out of nine Fabric
GameTest harnesses, each with its own generated `fabric.mod.json`, driven by a 476-line Loom build
that swaps manifests between runs. Fabric GameTest v1 is the thing the harness roster is built on.
A multi-loader restructure moves all of that at once, and it is the part that cannot be checked by
running the tests, because the tests are what is being moved.

## The two ways to ship it, and why neither is worth it now

1. **Ship NeoForge with the audited tier unavailable.** Cheapest by a wide margin, and the shipped
   default is `UNSAFE` anyway, so most users would not notice. But the audited tier is the one thing
   that distinguishes this mod from an 18 KB competitor with no published measurement. Shipping a
   build where it cannot exist sells the differentiator for reach.
2. **Build the second gate and the second harness set.** Correct, and considerably larger than
   everything 0.9.0 contains.

## What would change this

- A NeoForge user asking for it. Right now the demand is inferred from a competitor's version list,
  which is the weakest evidence on this page.
- The gate being rebuilt around a loader-neutral reader of mod metadata, which is worth doing on its
  own merits if a second loader is ever wanted. That is the real prerequisite, and it belongs to
  whichever release wants to pay for it.

Until one of those happens, this is abandoned rather than pending.
