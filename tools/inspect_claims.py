"""Which METHOD does each blocking mixin actually inject into?

The gate denies on class-level claims: a mod that touches BlockStateBase at all denies every
evaluator family. This asks the narrower question the gate should be asking -- which method is
injected -- so we can see how many of the blockers are irrelevant to pathfinding.
"""
import zipfile, json, io, os, sys, re, collections

WATCHED = {
 "net/minecraft/world/level/block/state/BlockBehaviour$BlockStateBase",
 "net/minecraft/world/level/pathfinder/NodeEvaluator",
 "net/minecraft/world/level/pathfinder/PathfindingContext",
 "net/minecraft/world/entity/ai/navigation/PathNavigation",
 "net/minecraft/world/level/pathfinder/PathFinder",
 "net/minecraft/world/level/PathNavigationRegion",
 "net/minecraft/world/level/pathfinder/Path",
 "net/minecraft/world/level/pathfinder/Node",
 "net/minecraft/world/level/pathfinder/WalkNodeEvaluator",
}
# Mixin injector annotations carry the target method name(s) in a `method` array of UTF8 strings.
INJECTORS = (b"Inject", b"Redirect", b"ModifyArg", b"ModifyArgs", b"ModifyConstant",
             b"ModifyVariable", b"ModifyExpressionValue", b"WrapOperation", b"WrapWithCondition",
             b"Overwrite", b"Accessor", b"Invoker")

def utf8_pool(data):
    """Every CONSTANT_Utf8 in the class file, in order -- enough to see method names and targets."""
    out=[]
    if data[:4] != b"\xca\xfe\xba\xbe": return out
    n=int.from_bytes(data[8:10],"big"); i=10; k=1
    while k < n:
        t=data[i]
        if t==1:
            ln=int.from_bytes(data[i+1:i+3],"big")
            try: out.append(data[i+3:i+3+ln].decode("utf-8"))
            except Exception: out.append("")
            i+=3+ln
        elif t in (7,8,16,19,20): i+=3
        elif t==15: i+=4
        elif t in (5,6): i+=9; k+=1
        else: i+=5
        k+=1
    return out

def cfgnames(meta):
    out=[]
    for e in meta.get("mixins",[]):
        if isinstance(e,str): out.append(e)
        elif isinstance(e,dict) and "config" in e: out.append(e["config"])
    return out

def scan(z, hits, origin):
    try: meta=json.loads(z.read("fabric.mod.json"))
    except Exception: return
    mid=meta.get("id","?")
    for cfgname in cfgnames(meta):
        try: cfg=json.loads(z.read(cfgname).decode("utf-8-sig"))
        except Exception: continue
        pkg=cfg.get("package","")
        for section in ("mixins","server"):
            for cls in cfg.get(section,[]):
                fq=(pkg+"."+cls) if pkg else cls
                try: data=z.read(fq.replace(".","/")+".class")
                except Exception: continue
                pool=utf8_pool(data)
                targets=[t for t in WATCHED if any(t==p or ("L"+t+";")==p for p in pool)]
                if not targets: continue
                injects = any(any(inj in p.encode() for inj in INJECTORS) for p in pool
                              if p.startswith("Lorg/spongepowered"))
                # method names the injectors name: plain identifiers or name+descriptor forms
                methods = sorted({p.split("(")[0] for p in pool
                                  if re.fullmatch(r"[a-zA-Z_$][\w$]*(\(.*)?", p or "")
                                  and p.split("(")[0] not in ("", "this")
                                  and "(" in p})
                hits[mid].append((fq.split(".")[-1], targets, methods[:6]))

hits=collections.defaultdict(list)
MODS=sys.argv[1]
for fn in sorted(os.listdir(MODS)):
    if not fn.endswith(".jar"): continue
    try: z=zipfile.ZipFile(os.path.join(MODS,fn))
    except Exception: continue
    scan(z,hits,fn)
    for n in z.namelist():
        if n.endswith(".jar"):
            try: scan(zipfile.ZipFile(io.BytesIO(z.read(n))),hits,fn)
            except Exception: pass

only_bsb=[]; other=[]
for mid,rows in sorted(hits.items()):
    if mid=="pathweaver": continue
    tset={t for _,ts,_ in rows for t in ts}
    (only_bsb if tset=={"net/minecraft/world/level/block/state/BlockBehaviour$BlockStateBase"}
        else other).append((mid,rows,tset))

print(f"=== claim ONLY BlockStateBase ({len(only_bsb)}) — candidates for method-level clearing ===")
for mid,rows,_ in only_bsb:
    for cls,ts,ms in rows:
        print(f"  {mid:28s} {cls[:44]:46s} methods~ {', '.join(ms[:4])}")
print(f"\n=== claim a pathfinding type ({len(other)}) — must stay gated or be audited ===")
for mid,rows,tset in other:
    print(f"  {mid:28s} {', '.join(sorted(t.split('/')[-1] for t in tset))}")
