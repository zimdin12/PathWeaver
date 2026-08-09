import zipfile, json, io, os, sys, collections

SHARED = [
 "net/minecraft/world/level/pathfinder/NodeEvaluator",
 "net/minecraft/world/level/pathfinder/PathfindingContext",
 "net/minecraft/world/entity/ai/navigation/PathNavigation",
 "net/minecraft/world/entity/ai/navigation/GroundPathNavigation",
 "net/minecraft/world/level/pathfinder/PathFinder",
 "net/minecraft/world/level/PathNavigationRegion",
 "net/minecraft/world/level/block/state/BlockBehaviour$BlockStateBase",
 "net/minecraft/world/level/pathfinder/PathTypeCache",
 "net/minecraft/world/entity/ai/navigation/WaterBoundPathNavigation",
 "net/minecraft/world/entity/ai/navigation/AmphibiousPathNavigation",
 "net/minecraft/world/entity/ai/navigation/FlyingPathNavigation",
 "net/minecraft/world/level/pathfinder/BinaryHeap",
 "net/minecraft/world/level/pathfinder/Node",
 "net/minecraft/world/level/pathfinder/Path",
 "net/minecraft/world/level/pathfinder/Target",
]
EVAL = [
 "net/minecraft/world/level/pathfinder/WalkNodeEvaluator",
 "net/minecraft/world/level/pathfinder/SwimNodeEvaluator",
 "net/minecraft/world/level/pathfinder/AmphibiousNodeEvaluator",
 "net/minecraft/world/level/pathfinder/FlyNodeEvaluator",
 "net/minecraft/world/entity/animal/frog/Frog$FrogNodeEvaluator",
 "net/minecraft/world/entity/monster/creaking/Creaking$HomeNodeEvaluator",
]
WATCH = {t: ("evaluator" if t in EVAL else "shared") for t in SHARED + EVAL}

def cfgnames(meta):
    out = []
    for e in meta.get("mixins", []):
        if isinstance(e, str): out.append(e)
        elif isinstance(e, dict) and "config" in e: out.append(e["config"])
    return out

def scan(z, hits, origin):
    try: meta = json.loads(z.read("fabric.mod.json"))
    except Exception: return
    mid = meta.get("id", "?"); ver = meta.get("version", "?")
    for cfgname in cfgnames(meta):
        try: cfg = json.loads(z.read(cfgname).decode("utf-8-sig"))
        except Exception: continue
        pkg = cfg.get("package", "")
        for section in ("mixins", "server"):
            for cls in cfg.get(section, []):
                fq = (pkg + "." + cls) if pkg else cls
                try: data = z.read(fq.replace(".", "/") + ".class")
                except Exception: continue
                for t, kind in WATCH.items():
                    if (b"L" + t.encode() + b";") in data or t.encode() in data:
                        hits[(mid, ver, origin)].append((cfgname, fq, t, kind))

hits = collections.defaultdict(list)
MODS = sys.argv[1]
for fn in sorted(os.listdir(MODS)):
    if not fn.endswith(".jar"): continue
    p = os.path.join(MODS, fn)
    try: z = zipfile.ZipFile(p)
    except Exception: continue
    scan(z, hits, fn)
    for n in z.namelist():                      # nested jars (fabric-api aggregate)
        if n.endswith(".jar"):
            try: scan(zipfile.ZipFile(io.BytesIO(z.read(n))), hits, fn + "!" + os.path.basename(n))
            except Exception: pass

print(f"scanned {len([f for f in os.listdir(MODS) if f.endswith('.jar')])} jars\n")
rows = sorted(hits.items(), key=lambda kv: -len(kv[1]))
denies_all, denies_family = [], []
for (mid, ver, origin), claims in rows:
    kinds = {c[3] for c in claims}
    tgts = sorted({c[2].split("/")[-1] for c in claims})
    (denies_all if "shared" in kinds else denies_family).append((mid, ver, tgts, origin))

print("=== CLAIMS A SHARED TARGET -> denies ALL SIX families ===")
for mid, ver, tgts, origin in denies_all:
    print(f"  {mid:34s} {ver:24s} {', '.join(tgts)}")
print(f"\n=== CLAIMS ONLY AN EVALUATOR -> denies just that family ===")
for mid, ver, tgts, origin in denies_family:
    print(f"  {mid:34s} {ver:24s} {', '.join(tgts)}")
# By mod id, not by row. A mod with three mixin configs produced three rows, so the totals line said
# 22 where the pack has 20 distinct mods -- and the README, which quotes the distinct count, read as
# stale to anyone who ran the tool.
uniq_all = {mid for mid, _, _, _ in denies_all}
uniq_family = {mid for mid, _, _, _ in denies_family} - uniq_all
print()
print(f"totals: {len(uniq_all)} mods deny everything, {len(uniq_family)} deny one family "
      f"({len(denies_all) + len(denies_family)} mixin configs across them)")
