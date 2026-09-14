#!/usr/bin/env python3
"""Static scan of a mod pack for path-request call sites.

Finds bytecode call sites that ask a mob to (re)plan a path:

  navigation      PathNavigation-family moveTo / createPath / recomputePath
                  (the family is DERIVED: every class whose superclass chain
                  reaches PathNavigation, in vanilla or in any scanned jar)
  behavior_utils  BehaviorUtils.setWalkAndLookTargetMemories
  walk_target     a WalkTarget constructed in a method that also calls a memory
                  setter (Brain.setMemory*, MemoryAccessor.set*) - heuristic

For each site it records whether the containing method looks tick-driven, whether
the target is an Entity (a moving target), and two weak hints: a throttle in the
same method, and whether the call sits inside a loop over a list or iterator.

Standard library only. Reads jars; writes only the CSV named by --csv.

  python path_request_scan.py --mods <mods dir> --vanilla <26.1.2.jar>
         [--extra <jar> ...] [--only <jar name> ...] [--csv out.csv] [--depth 4]

Decoder check (independent instrument): disassemble the same classes with javap
and compare every request-named invoke. Exit status 1 on any mismatch.

  python path_request_scan.py --mods <mods dir> --vanilla <jar> --javap <javap> [--javap-all-classes]

Method, controls and results: docs/research/path-request-patterns-2026-09.md
"""
from __future__ import annotations

import argparse
import csv
import io
import json
import os
import re
import struct
import sys
import zipfile
from collections import defaultdict
from dataclasses import dataclass, asdict

# --- Minecraft names this scan is about (26.1.2, unobfuscated) -------------
NAVIGATION_ROOT = "net/minecraft/world/entity/ai/navigation/PathNavigation"
REQUEST_METHODS = frozenset({"moveTo", "createPath", "recomputePath"})
NAV_STATE_METHODS = frozenset({"isDone", "isInProgress", "isStuck", "getPath", "getTargetPos", "stop"})
TICK_DELAY_METHODS = frozenset({"adjustedTickDelay", "reducedTickDelay"})  # Goal's throttle helpers
OP_IREM, OP_LREM, OP_GOTO, OP_GOTO_W = 0x70, 0x71, 0xA7, 0xC8
LOOP_ELEMENT_READS = {("java/util/Iterator", "next"), ("java/util/List", "get")}
BEHAVIOR_UTILS = "net/minecraft/world/entity/ai/behavior/BehaviorUtils"
WALK_AND_LOOK = "setWalkAndLookTargetMemories"
WALK_TARGET = "net/minecraft/world/entity/ai/memory/WalkTarget"
MEMORY_SETTERS = {
    "net/minecraft/world/entity/ai/Brain": frozenset({"setMemory", "setMemoryWithExpiry"}),
    "net/minecraft/world/entity/ai/behavior/declarative/MemoryAccessor": frozenset({"set", "setWithExpiry", "setOrErase"}),
}
ENTITY_ROOT = "net/minecraft/world/entity/Entity"
GOAL_ROOT = "net/minecraft/world/entity/ai/goal/Goal"
BEHAVIOR_ROOT = "net/minecraft/world/entity/ai/behavior/Behavior"
# Methods the game calls every tick (or every goal/behavior evaluation).
TICK_METHODS = {
    ENTITY_ROOT: frozenset({"tick", "aiStep", "serverAiStep", "customServerAiStep"}),
    GOAL_ROOT: frozenset({"canUse", "canContinueToUse", "start", "tick"}),
    BEHAVIOR_ROOT: frozenset({"tick", "checkExtraStartConditions", "canStillUse"}),
    NAVIGATION_ROOT: frozenset({"tick"}),
}
ALL_TICK_NAMES = frozenset().union(*TICK_METHODS.values())
MIXIN_ANNOTATION = "Lorg/spongepowered/asm/mixin/Mixin;"
REQUEST_NAME_BYTES = (b"moveTo", b"createPath", b"recomputePath", WALK_AND_LOOK.encode(), b"ai/memory/WalkTarget")

OP_INVOKEVIRTUAL, OP_INVOKESPECIAL, OP_INVOKESTATIC, OP_INVOKEINTERFACE = 0xB6, 0xB7, 0xB8, 0xB9
OP_NAMES = {0xB6: "invokevirtual", 0xB7: "invokespecial", 0xB8: "invokestatic", 0xB9: "invokeinterface"}


# --- class file parsing (pure functions over bytes) ------------------------
def _instruction_length(code: bytes, pc: int) -> int:
    op = code[pc]
    if op in (0x10, 0x12, 0xA9, 0xBC) or 0x15 <= op <= 0x19 or 0x36 <= op <= 0x3A:
        return 2
    if op in (0x11, 0x13, 0x14, 0x84, 0xBB, 0xBD, 0xC0, 0xC1, 0xC6, 0xC7) or 0x99 <= op <= 0xA8 or 0xB2 <= op <= 0xB8:
        return 3
    if op == 0xC5:
        return 4
    if op in (0xB9, 0xBA, 0xC8, 0xC9):
        return 5
    if op == 0xC4:  # wide
        return 6 if code[pc + 1] == 0x84 else 4
    if op in (0xAA, 0xAB):  # tableswitch / lookupswitch
        base = pc + 1 + ((4 - (pc + 1) % 4) % 4)
        if op == 0xAA:
            low, high = struct.unpack_from(">ii", code, base + 4)
            return base + 12 + 4 * (high - low + 1) - pc
        (npairs,) = struct.unpack_from(">i", code, base + 4)
        return base + 8 + 8 * npairs - pc
    return 1


@dataclass
class Instruction:
    pc: int
    op: int
    cp_index: int


@dataclass
class Method:
    name: str
    desc: str
    instructions: list[Instruction]
    annotations: list[dict]


@dataclass
class ClassFile:
    name: str
    super_name: str | None
    interfaces: list[str]
    methods: list[Method]
    annotations: list[dict]
    cp: list

    def utf8(self, i: int) -> str:
        return self.cp[i][1]

    def class_name(self, i: int) -> str:
        return self.utf8(self.cp[i][1])

    def member_ref(self, i: int) -> tuple[str, str, str]:
        _, cls, nat = self.cp[i]
        _, n, d = self.cp[nat]
        return self.class_name(cls), self.utf8(n), self.utf8(d)


def _read_annotation(cp, data: bytes, off: int) -> tuple[dict, int]:
    type_idx, pairs = struct.unpack_from(">HH", data, off)
    off += 4
    values = {}
    for _ in range(pairs):
        (name_idx,) = struct.unpack_from(">H", data, off)
        value, off = _read_element(cp, data, off + 2)
        values[cp[name_idx][1]] = value
    return {"type": cp[type_idx][1], "values": values}, off


def _read_element(cp, data: bytes, off: int):
    tag = chr(data[off])
    off += 1
    if tag in "BCDFIJSZs":
        (i,) = struct.unpack_from(">H", data, off)
        entry = cp[i]
        return (entry[1] if entry and entry[0] == 1 else None), off + 2
    if tag == "e":
        return None, off + 4
    if tag == "c":
        (i,) = struct.unpack_from(">H", data, off)
        return cp[i][1], off + 2
    if tag == "@":
        return _read_annotation(cp, data, off)
    if tag == "[":
        (n,) = struct.unpack_from(">H", data, off)
        off += 2
        items = []
        for _ in range(n):
            v, off = _read_element(cp, data, off)
            items.append(v)
        return items, off
    raise ValueError(f"bad annotation element tag {tag!r}")


def _read_annotations(cp, data: bytes) -> list[dict]:
    (n,) = struct.unpack_from(">H", data, 0)
    off, out = 2, []
    for _ in range(n):
        a, off = _read_annotation(cp, data, off)
        out.append(a)
    return out


def parse_class(data: bytes, with_code: bool) -> ClassFile:
    if data[:4] != b"\xca\xfe\xba\xbe":
        raise ValueError("not a class file")
    (count,) = struct.unpack_from(">H", data, 8)
    cp: list = [None] * count
    off, i = 10, 1
    while i < count:
        tag = data[off]
        if tag == 1:
            (ln,) = struct.unpack_from(">H", data, off + 1)
            cp[i] = (1, data[off + 3: off + 3 + ln].decode("utf-8", "replace"))
            off += 3 + ln
        elif tag in (7, 8, 16, 19, 20):
            cp[i] = (tag, struct.unpack_from(">H", data, off + 1)[0])
            off += 3
        elif tag in (9, 10, 11, 12, 17, 18):
            a, b = struct.unpack_from(">HH", data, off + 1)
            cp[i] = (tag, a, b)
            off += 5
        elif tag in (3, 4):
            off += 5
        elif tag in (5, 6):
            off += 9
            i += 1
        elif tag == 15:
            off += 4
        else:
            raise ValueError(f"bad constant tag {tag}")
        i += 1
    _, this_idx, super_idx, n_ifaces = struct.unpack_from(">HHHH", data, off)
    off += 8
    cname = lambda idx: cp[cp[idx][1]][1]
    interfaces = [cname(struct.unpack_from(">H", data, off + 2 * k)[0]) for k in range(n_ifaces)]
    off += 2 * n_ifaces
    cf = ClassFile(cname(this_idx), cname(super_idx) if super_idx else None, interfaces, [], [], cp)
    if not with_code:
        return cf
    (n_fields,) = struct.unpack_from(">H", data, off)
    off += 2
    for _ in range(n_fields):
        off = _skip_attributes(data, off + 6)
    (n_methods,) = struct.unpack_from(">H", data, off)
    off += 2
    for _ in range(n_methods):
        _, name_idx, desc_idx, n_attr = struct.unpack_from(">HHHH", data, off)
        off += 8
        method = Method(cp[name_idx][1], cp[desc_idx][1], [], [])
        for _ in range(n_attr):
            attr_name_idx, ln = struct.unpack_from(">HI", data, off)
            body = data[off + 6: off + 6 + ln]
            attr = cp[attr_name_idx][1]
            if attr == "Code":
                (code_len,) = struct.unpack_from(">I", body, 4)
                method.instructions = _invokes(body[8: 8 + code_len])
            elif attr in ("RuntimeInvisibleAnnotations", "RuntimeVisibleAnnotations"):
                method.annotations += _read_annotations(cp, body)
            off += 6 + ln
        cf.methods.append(method)
    (n_attr,) = struct.unpack_from(">H", data, off)
    off += 2
    for _ in range(n_attr):
        attr_name_idx, ln = struct.unpack_from(">HI", data, off)
        if cp[attr_name_idx][1] in ("RuntimeInvisibleAnnotations", "RuntimeVisibleAnnotations"):
            cf.annotations += _read_annotations(cp, data[off + 6: off + 6 + ln])
        off += 6 + ln
    return cf


def _skip_attributes(data: bytes, off: int) -> int:
    (n,) = struct.unpack_from(">H", data, off)
    off += 2
    for _ in range(n):
        (ln,) = struct.unpack_from(">I", data, off + 2)
        off += 6 + ln
    return off


def _invokes(code: bytes) -> list[Instruction]:
    """Member-referencing instructions (field access, invokes, new) with their cp index, plus integer modulo."""
    out, pc = [], 0
    while pc < len(code):
        op = code[pc]
        if 0xB2 <= op <= 0xB9 or op == 0xBB:
            out.append(Instruction(pc, op, struct.unpack_from(">H", code, pc + 1)[0]))
        elif op in (OP_IREM, OP_LREM):
            out.append(Instruction(pc, op, -1))
        elif op in (OP_GOTO, OP_GOTO_W):  # backward jumps close loops; cp_index holds the jump target
            offset = struct.unpack_from(">h", code, pc + 1)[0] if op == OP_GOTO else struct.unpack_from(">i", code, pc + 1)[0]
            if offset < 0:
                out.append(Instruction(pc, op, pc + offset))
        pc += _instruction_length(code, pc)
    return out


# --- pack model ------------------------------------------------------------
@dataclass
class JarUnit:
    """One jar (top level or nested) with the mod it belongs to."""
    label: str
    mod_id: str
    version: str
    zf: zipfile.ZipFile


def read_mod_identity(zf: zipfile.ZipFile, fallback: str) -> tuple[str, str]:
    for meta in ("fabric.mod.json", "quilt.mod.json"):
        try:
            raw = zf.read(meta).decode("utf-8", "replace")
        except KeyError:
            continue
        try:
            doc = json.loads(raw, strict=False)
            doc = doc.get("quilt_loader", doc)
            return str(doc.get("id", fallback)), str(doc.get("version", "?"))
        except ValueError:
            m = re.search(r'"id"\s*:\s*"([^"]+)"', raw)
            if m:
                return m.group(1), "?"
    return fallback, "?"


def open_units(path: str, label: str) -> list[JarUnit]:
    zf = zipfile.ZipFile(path)
    return _units_of(zf, label)


def _units_of(zf: zipfile.ZipFile, label: str) -> list[JarUnit]:
    stem = re.sub(r"\.jar$", "", label.rsplit("/", 1)[-1])
    mod_id, version = read_mod_identity(zf, stem)
    units = [JarUnit(label, mod_id, version, zf)]
    for name in zf.namelist():
        if name.startswith("META-INF/jars/") and name.endswith(".jar"):
            inner = zipfile.ZipFile(io.BytesIO(zf.read(name)))
            units += _units_of(inner, f"{label}!/{name}")
    return units


class Hierarchy:
    """Lazy superclass/interface resolution across vanilla and every scanned jar."""

    def __init__(self, units: list[JarUnit]):
        self._where: dict[str, tuple[zipfile.ZipFile, str]] = {}
        for u in units:
            for n in u.zf.namelist():
                if n.endswith(".class") and not n.startswith("META-INF/"):
                    self._where.setdefault(n[:-6], (u.zf, n))
        self._parents: dict[str, list[str]] = {}
        self._subtype: dict[tuple[str, str], bool] = {}

    def parents(self, name: str) -> list[str]:
        if name not in self._parents:
            loc = self._where.get(name)
            result: list[str] = []
            if loc:
                try:
                    cf = parse_class(loc[0].read(loc[1]), with_code=False)
                    result = ([cf.super_name] if cf.super_name else []) + cf.interfaces
                except Exception:
                    pass
            self._parents[name] = result
        return self._parents[name]

    def is_subtype(self, name: str, root: str) -> bool:
        key = (name, root)
        if key not in self._subtype:
            self._subtype[key] = False  # cycle guard
            self._subtype[key] = name == root or any(self.is_subtype(p, root) for p in self.parents(name))
        return self._subtype[key]

    def resolved(self, name: str) -> bool:
        return name in self._where

    def location(self, name: str) -> tuple[zipfile.ZipFile, str] | None:
        return self._where.get(name)


@dataclass
class CallSite:
    mod_id: str
    mod_version: str
    jar: str
    class_name: str
    method: str
    method_desc: str
    pc: int
    opcode: str
    kind: str
    callee_owner: str
    callee_name: str
    callee_desc: str
    target_kind: str
    entity_target: bool
    requests_search: bool
    inside_navigation: bool
    tick_driven: bool
    tick_evidence: str
    cadence: str
    tick_reason: str
    method_checks_nav_state: bool
    throttle_hint: bool
    in_iterator_loop: bool
    mixin_targets: str

    @property
    def method_key(self) -> tuple[str, str]:
        return self.class_name, self.method + self.method_desc


def param_types(desc: str) -> list[str]:
    types, i, params = [], 0, desc[1: desc.index(")")]
    while i < len(params):
        j = i
        while params[j] == "[":
            j += 1
        j = params.index(";", j) + 1 if params[j] == "L" else j + 1
        types.append(params[i:j])
        i = j
    return types


def target_kind(kind: str, name: str, desc: str) -> str:
    """What the request aims at. BehaviorUtils' first parameter is the mob itself, so it is skipped."""
    params = param_types(desc)[1:] if kind == "behavior_utils" else param_types(desc)
    first = params[0] if params else ""
    if first.endswith("/PositionTracker;"):
        return "tracker"
    if first.startswith("Lnet/minecraft/world/entity/"):
        return "entity"
    if first == "Lnet/minecraft/world/level/pathfinder/Path;":
        return "path"
    if first == "Lnet/minecraft/core/BlockPos;":
        return "blockpos"
    if first in ("Ljava/util/Set;", "Ljava/util/stream/Stream;"):
        return "blockpos_set"
    if first == "Lnet/minecraft/world/phys/Vec3;":
        return "vec3"
    if first == "D":
        return "coords"
    return "current_target" if name == "recomputePath" else "other:" + first


def cadence_of(reason: str) -> str:
    """'on_start' when the root of the tick chain is Goal.start (once per activation), else 'every_tick'."""
    if not reason:
        return ""
    return "on_start" if reason.rsplit("<- ", 1)[-1].split("(")[0].endswith("Goal.start") else "every_tick"


def evidence_of(reason: str) -> str:
    if not reason:
        return ""
    if "#" in reason:  # cross-class links are labelled Class#method
        return "cross_class"
    return "same_class" if reason.startswith("via ") else "direct"


def mixin_selector_name(selector: str) -> str:
    s = selector
    if s.startswith("L") and ";" in s:
        s = s.split(";", 1)[1]
    return s.split("(", 1)[0].split(":", 1)[0].rstrip("*")


def in_iterator_loop(pc: int, loops: list[tuple[int, int]], element_reads: list[int]) -> bool:
    """True when pc lies inside a backward-jump loop whose body reads a list or iterator element."""
    return any(start <= pc <= end and any(start <= r <= end for r in element_reads) for start, end in loops)


def lambda_parent(method_name: str) -> str | None:
    m = re.match(r"lambda\$(.+)\$\d+$", method_name)
    return m.group(1) if m else None


def invokes(cf: ClassFile, m: Method):
    for ins in m.instructions:
        if OP_INVOKEVIRTUAL <= ins.op <= OP_INVOKEINTERFACE:
            yield ins, cf.member_ref(ins.cp_index)


class ClassScan:
    """Call sites and tick-driven reasoning for one parsed class."""

    def __init__(self, cf: ClassFile, unit: JarUnit, hierarchy: Hierarchy):
        self.cf, self.unit, self.h = cf, unit, hierarchy
        self.mixin_targets = self._mixin_targets()
        self.roles = self._roles()
        self._reasons: dict[str, str] | None = None

    def _mixin_targets(self) -> list[str]:
        for a in self.cf.annotations:
            if a["type"] == MIXIN_ANNOTATION:
                vals = a["values"]
                targets = [v[1:-1] for v in (vals.get("value") or []) if isinstance(v, str) and v.startswith("L")]
                targets += [t.replace(".", "/") for t in (vals.get("targets") or []) if isinstance(t, str)]
                return targets
        return []

    def _roles(self) -> list[str]:
        subjects = [self.cf.name] + self.mixin_targets
        return [root for root in TICK_METHODS if any(self.h.is_subtype(s, root) for s in subjects)]

    def is_navigation(self, owner: str) -> bool:
        """A navigation-family owner; a mixin's own class counts as the classes it targets."""
        if owner == self.cf.name and any(self.h.is_subtype(t, NAVIGATION_ROOT) for t in self.mixin_targets):
            return True
        return self.h.is_subtype(owner, NAVIGATION_ROOT)

    def _direct_tick_reason(self, m: Method) -> str:
        name, prefix = m.name, ""
        if (parent := lambda_parent(m.name)) is not None:
            name, prefix = parent, "lambda-in-"
        for root in self.roles:
            if name in TICK_METHODS[root]:
                return f"{prefix}{root.rsplit('/', 1)[1]}.{name}"
        for a in m.annotations:
            selectors = a["values"].get("method")
            if not isinstance(selectors, list) or "mixin" not in a["type"]:
                continue
            for sel in selectors:
                target = mixin_selector_name(sel) if isinstance(sel, str) else ""
                for root in self.roles:
                    if target in TICK_METHODS[root]:
                        return f"mixin@{root.rsplit('/', 1)[1]}.{target}"
                if not self.roles and target in ALL_TICK_NAMES:
                    return f"mixin@{target}(target type unresolved)"
        return ""

    def tick_reasons(self) -> dict[str, str]:
        """Direct reasons, then propagated through same-class calls to a fixed point. Keyed by name+desc."""
        if self._reasons is not None:
            return self._reasons
        key = lambda m: m.name + m.desc
        reasons = {key(m): r for m in self.cf.methods if (r := self._direct_tick_reason(m))}
        callees: dict[str, set[str]] = defaultdict(set)
        for m in self.cf.methods:
            for _, (owner, n, d) in invokes(self.cf, m):
                if owner == self.cf.name:
                    callees[key(m)].add(n + d)
        frontier = list(reasons)
        while frontier:
            caller = frontier.pop()
            for callee in callees.get(caller, ()):
                if callee not in reasons:
                    reasons[callee] = f"via {caller.split('(')[0]} <- {reasons[caller]}"
                    frontier.append(callee)
        self._reasons = reasons
        return reasons

    def call_sites(self) -> tuple[list[CallSite], list[tuple]]:
        reasons = self.tick_reasons()
        inside_nav = self.is_navigation(self.cf.name)
        sites, rejected = [], []
        for m in self.cf.methods:
            refs = list(invokes(self.cf, m))
            news = {self.cf.class_name(ins.cp_index) for ins in m.instructions if ins.op == 0xBB}
            checks_state = any(n in NAV_STATE_METHODS and self.is_navigation(o) for _, (o, n, _) in refs)
            sets_memory = any(n in MEMORY_SETTERS.get(o, ()) for _, (o, n, _) in refs)
            throttle = any(n in TICK_DELAY_METHODS for _, (_, n, _) in refs) or any(i.op in (OP_IREM, OP_LREM) for i in m.instructions)
            loops = [(i.cp_index, i.pc) for i in m.instructions if i.op in (OP_GOTO, OP_GOTO_W)]
            element_reads = [i.pc for i, (o, n, _) in refs if (o, n) in LOOP_ELEMENT_READS]
            reason = reasons.get(m.name + m.desc, "")
            for ins, (owner, name, desc) in refs:
                kind = self._kind(ins.op, owner, name, sets_memory, news)
                if kind is None:
                    if name in REQUEST_METHODS:
                        rejected.append((self.cf.name, m.name, owner, name + desc, self.h.resolved(owner)))
                    continue
                tk = target_kind(kind, name, desc)
                sites.append(CallSite(
                    self.unit.mod_id, self.unit.version, self.unit.label, self.cf.name, m.name, m.desc, ins.pc,
                    OP_NAMES[ins.op], kind, owner, name, desc, tk, tk == "entity",
                    not (name == "moveTo" and tk == "path"), inside_nav, bool(reason), evidence_of(reason),
                    cadence_of(reason), reason, checks_state, throttle,
                    in_iterator_loop(ins.pc, loops, element_reads), ";".join(self.mixin_targets)))
        return sites, rejected

    def _kind(self, op: int, owner: str, name: str, sets_memory: bool, news: set[str]) -> str | None:
        if name in REQUEST_METHODS and op != OP_INVOKESTATIC and self.is_navigation(owner):
            return "navigation"
        if op == OP_INVOKESTATIC and owner == BEHAVIOR_UTILS and name == WALK_AND_LOOK:
            return "behavior_utils"
        if op == OP_INVOKESPECIAL and owner == WALK_TARGET and name == "<init>" and sets_memory and WALK_TARGET in news:
            return "walk_target"
        return None


def iter_classes(units: list[JarUnit]):
    for u in units:
        for n in u.zf.namelist():
            if n.endswith(".class") and not n.startswith("META-INF/"):
                yield u, n


class CrossClassResolver:
    """Bounded reverse call search: links a method with no tick reason to a tick-driven caller in another class.

    A call (owner O, name, desc) is taken to reach method name+desc declared in class C when O == C, O is a
    subtype of C (inherited), or C is a subtype of O (virtual dispatch). Downward dispatch through a JDK or
    Minecraft type (Runnable, PathNavigation, ...) is accepted only from a caller in the same jar as C,
    otherwise every override in the pack would inherit every caller. A synthetic lambda is linked to the
    method that creates it, which says where it was made, not how often it runs. These links are
    reported as evidence level 'cross_class' and the edge kind is spelled out in the reason.
    """

    def __init__(self, units: list[JarUnit], hierarchy: Hierarchy, max_depth: int):
        self.units, self.h, self.max_depth = units, hierarchy, max_depth

    def _dispatches_to(self, owner: str, declaring: str, same_jar: bool) -> bool:
        if owner == declaring or self.h.is_subtype(owner, declaring):
            return True
        platform_type = owner.startswith(("java/", "net/minecraft/"))
        return (same_jar or not platform_type) and self.h.is_subtype(declaring, owner)

    def resolve(self, unreasoned: set[tuple[str, str]]) -> dict[tuple[str, str], str]:
        reasons: dict[tuple[str, str], str] = {}
        callers_of: dict[tuple[str, str], set[tuple[tuple[str, str], str]]] = defaultdict(set)
        frontier, seen = set(unreasoned), set(unreasoned)
        for _ in range(self.max_depth):
            if not frontier:
                break
            frontier = self._one_level(frontier, seen, reasons, callers_of)
        return self._propagate(reasons, callers_of)

    def _one_level(self, frontier, seen, reasons, callers_of) -> set[tuple[str, str]]:
        by_key: dict[str, list[str]] = defaultdict(list)
        jar_of = self._jar_of
        lambda_children: dict[tuple[str, str], list[tuple[str, str]]] = defaultdict(list)
        for cls, mk in frontier:
            by_key[mk].append(cls)
            if (parent := lambda_parent(mk.split("(")[0])) is not None:
                lambda_children[(cls, parent)].append((cls, mk))
        needles = {mk.split("(")[0].encode() for mk in by_key}
        lambda_classes = {cls for cls, _ in lambda_children}
        new: set[tuple[str, str]] = set()
        for u, entry in iter_classes(self.units):
            data = u.zf.read(entry)
            if entry[:-6] not in lambda_classes and not any(nd in data for nd in needles):
                continue
            cf = parse_class(data, with_code=True)
            scan = None
            for m in cf.methods:
                callees = [(c, "lambda") for c in lambda_children.get((cf.name, m.name), ())]
                callees += [((c, n + d), "call") for _, (o, n, d) in invokes(cf, m) for c in by_key.get(n + d, ())
                            if self._dispatches_to(o, c, jar_of(c) is u.zf)]
                if not callees:
                    continue
                caller = (cf.name, m.name + m.desc)
                for callee, edge in callees:
                    if callee != caller:
                        callers_of[callee].add((caller, edge))
                scan = scan or ClassScan(cf, u, self.h)
                if reason := scan.tick_reasons().get(caller[1]):
                    reasons.setdefault(caller, reason)
                elif caller not in seen:
                    seen.add(caller)
                    new.add(caller)
        return new

    def _jar_of(self, class_name: str):
        loc = self.h.location(class_name)
        return loc[0] if loc else None

    @staticmethod
    def _propagate(reasons, callers_of) -> dict[tuple[str, str], str]:
        callees_of: dict[tuple[str, str], set[tuple[tuple[str, str], str]]] = defaultdict(set)
        for callee, callers in callers_of.items():
            for caller, edge in callers:
                callees_of[caller].add((callee, edge))
        queue = sorted(reasons)
        while queue:
            caller = queue.pop()
            for callee, edge in sorted(callees_of.get(caller, ())):
                if callee not in reasons:
                    label = f"{caller[0].rsplit('/', 1)[-1]}#{caller[1].split('(')[0]}"
                    verb = "via" if edge == "call" else "lambda created in"
                    reasons[callee] = f"{verb} {label} <- {reasons[caller]}"
                    queue.append(callee)
        return reasons


# --- driver ----------------------------------------------------------------
def scan_units(units: list[JarUnit], hierarchy: Hierarchy, stats: dict) -> tuple[list[CallSite], list]:
    sites, rejected = [], []
    for u, n in iter_classes(units):
        stats["classes"] += 1
        data = u.zf.read(n)
        if not any(b in data for b in REQUEST_NAME_BYTES):
            continue  # a Methodref to these names needs the literal Utf8 bytes
        stats["parsed"] += 1
        try:
            cf = parse_class(data, with_code=True)
        except Exception as e:  # noqa: BLE001 - report, never hide
            stats["errors"].append(f"{u.label}:{n}: {e}")
            continue
        s, r = ClassScan(cf, u, hierarchy).call_sites()
        sites += s
        rejected += [(u.mod_id, *x) for x in r]
    return sites, rejected


def dedupe(sites: list[CallSite]) -> list[CallSite]:
    seen, out = set(), []
    for s in sites:
        k = (s.mod_id, s.mod_version, s.class_name, s.method, s.method_desc, s.pc, s.callee_name, s.callee_desc)
        if k not in seen:
            seen.add(k)
            out.append(s)
    return out


def apply_cross_class(sites: list[CallSite], reasons: dict[tuple[str, str], str]) -> list[CallSite]:
    out = []
    for s in sites:
        r = s.tick_reason or reasons.get(s.method_key, "")
        out.append(CallSite(**{**asdict(s), "tick_driven": bool(r), "tick_reason": r,
                               "tick_evidence": evidence_of(r), "cadence": cadence_of(r)}))
    return out


def per_mod_table(sites: list[CallSite]) -> dict[str, dict[str, int]]:
    table: dict[str, dict[str, int]] = defaultdict(lambda: defaultdict(int))
    for s in sites:
        m = table[s.mod_id]
        m["sites"] += 1
        if s.inside_navigation:
            m["nav_impl"] += 1
            continue
        m[s.kind] += 1
        m["tick"] += s.tick_driven
        m["tick_same_or_direct"] += s.tick_evidence in ("direct", "same_class")
        m["entity"] += s.entity_target
        m["tick_entity"] += s.tick_driven and s.entity_target
        m["every_tick_entity"] += s.cadence == "every_tick" and s.entity_target
        m["every_tick_entity_unchecked"] += s.cadence == "every_tick" and s.entity_target and not s.method_checks_nav_state
        m["every_tick_entity_no_throttle_hint"] += s.cadence == "every_tick" and s.entity_target and not s.throttle_hint
        m["every_tick_entity_in_loop"] += s.cadence == "every_tick" and s.entity_target and s.in_iterator_loop
        m["every_tick_in_loop"] += s.cadence == "every_tick" and s.in_iterator_loop
    return table


def summarize(jar_count: int, unit_count: int, stats: dict, sites: list[CallSite], table, rejected, vanilla_sites) -> dict:
    count = lambda key: sum(1 for m in table.values() if m[key])
    return {
        "top_level_jars": jar_count, "jar_units_including_nested": unit_count, "classes": stats["classes"],
        "classes_parsed": stats["parsed"], "parse_errors": len(stats["errors"]), "sites": len(sites),
        "mods_with_any_site": len(table),
        "mods_with_caller_sites": sum(1 for m in table.values() if m["sites"] > m["nav_impl"]),
        "mods_with_nav_impl_only": sum(1 for m in table.values() if m["sites"] == m["nav_impl"]),
        "mods_with_tick_sites": count("tick"),
        "mods_with_tick_sites_direct_or_same_class": count("tick_same_or_direct"),
        "mods_with_entity_sites": count("entity"),
        "mods_with_tick_entity_sites": count("tick_entity"),
        "mods_with_every_tick_entity_sites": count("every_tick_entity"),
        "mods_with_every_tick_sites_in_loop": count("every_tick_in_loop"),
        "mods_with_every_tick_entity_sites_in_loop": count("every_tick_entity_in_loop"),
        "rejected_request_named_calls": len(rejected),
        "vanilla_baseline": {"sites": len(vanilla_sites), "tick_driven": sum(s.tick_driven for s in vanilla_sites),
                             "entity_target": sum(s.entity_target for s in vanilla_sites),
                             "every_tick_entity": sum(s.cadence == "every_tick" and s.entity_target for s in vanilla_sites),
                             "every_tick_entity_no_throttle_hint": sum(s.cadence == "every_tick" and s.entity_target and not s.throttle_hint for s in vanilla_sites),
                             "every_tick_in_loop": sum(s.cadence == "every_tick" and s.in_iterator_loop for s in vanilla_sites)},
    }


def load_units(args) -> tuple[list[str], list[JarUnit]]:
    jar_paths = sorted(os.path.join(args.mods, f) for f in os.listdir(args.mods) if f.endswith(".jar")) + args.extra
    if args.only:
        jar_paths = [p for p in jar_paths if os.path.basename(p) in args.only]
    units: list[JarUnit] = []
    for p in jar_paths:
        prefix = "mods/.disabled/" if ".disabled" in p.replace("\\", "/") else "mods/"
        units += open_units(p, prefix + os.path.basename(p))
    return jar_paths, units


def write_csv(path: str, sites: list[CallSite]) -> None:
    with open(path, "w", newline="", encoding="utf-8") as fh:
        w = csv.DictWriter(fh, fieldnames=list(CallSite.__dataclass_fields__))
        w.writeheader()
        for s in sorted(sites, key=lambda s: (s.mod_id, s.class_name, s.method, s.pc)):
            w.writerow(asdict(s))


# --- independent decoder check: javap disassembly vs this parser ------------
JAVAP_INVOKE = re.compile(r"^\s+(\d+): (invokevirtual|invokespecial|invokestatic|invokeinterface)\s+#\d+(?:,\s*\d+)?\s+// (?:Interface)?Method (.+?):(\(.*)$")
CHECKED_NAMES = REQUEST_METHODS | {WALK_AND_LOOK, "<init>"}


def _checked(owner: str, name: str) -> bool:
    return name in CHECKED_NAMES and (name != "<init>" or owner == WALK_TARGET)


def decoder_invokes(cf: ClassFile) -> list[tuple]:
    return sorted((cf.name, m.name, ins.pc, o, n, d) for m in cf.methods for ins, (o, n, d) in invokes(cf, m) if _checked(o, n))


def javap_invokes(text: str) -> list[tuple]:
    out, cls, method = [], None, None
    for line in text.splitlines():
        if line and not line[0].isspace() and line.rstrip().endswith("{"):  # the class declaration
            decl = re.split(r"\b(?:class|interface|enum|record)\s+", line, maxsplit=1)[-1]
            cls = re.split(r"[<\s{]", decl, maxsplit=1)[0].replace(".", "/")
            continue
        if line.startswith("  ") and not line.startswith("   ") and ("(" in line or line.strip() == "static {};"):
            head = line.split("(", 1)[0].split()
            method = "<clinit>" if line.strip() == "static {};" else ("<init>" if "." in head[-1] else head[-1])
            continue
        m = JAVAP_INVOKE.match(line)
        if m and cls:
            target, desc = m.group(3), m.group(4).strip()
            owner, name = target.rsplit(".", 1) if "." in target else (cls, target)
            name = name.strip('"')
            if _checked(owner, name):
                out.append((cls, method, int(m.group(1)), owner, name, desc))
    return sorted(out)


def javap_crosscheck(units: list[JarUnit], javap: str, prefilter: bool) -> tuple[int, int, list, list]:
    """Disassemble classes with javap and compare request-named invokes to the decoder.

    Returns (classes, invokes both agree on, only_decoder, only_javap)."""
    import subprocess
    import tempfile
    from collections import Counter
    decoded, files = Counter(), []
    with tempfile.TemporaryDirectory() as tmp:
        for i, (u, entry) in enumerate(iter_classes(units)):
            data = u.zf.read(entry)
            if prefilter and not any(b in data for b in REQUEST_NAME_BYTES):
                continue
            decoded.update(decoder_invokes(parse_class(data, with_code=True)))
            path = f"{tmp}/{i}.class"
            with open(path, "wb") as fh:
                fh.write(data)
            files.append(path)
        disassembled = Counter()
        for k in range(0, len(files), 80):
            run = subprocess.run([javap, "-c", "-p", *files[k:k + 80]], capture_output=True, text=True, encoding="utf-8", errors="replace")
            if run.returncode != 0:
                raise RuntimeError(f"javap failed: {run.stderr[:500]}")
            disassembled.update(javap_invokes(run.stdout))
    agreed = sum((decoded & disassembled).values())
    return len(files), agreed, sorted((decoded - disassembled).elements()), sorted((disassembled - decoded).elements())


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--mods", required=True)
    ap.add_argument("--vanilla", required=True, help="Minecraft jar: hierarchy source, and scanned as a baseline")
    ap.add_argument("--extra", action="append", default=[], help="additional jar to scan (e.g. a known positive)")
    ap.add_argument("--only", action="append", default=[], help="scan only these jar file names")
    ap.add_argument("--csv")
    ap.add_argument("--depth", type=int, default=4, help="cross-class caller search depth (0 disables)")
    ap.add_argument("--rejected", type=int, default=10, help="print this many rejected request-named calls")
    ap.add_argument("--javap", help="javap executable: only run the decoder-vs-javap cross-check and exit")
    ap.add_argument("--javap-all-classes", action="store_true", help="cross-check every class, not only prefiltered ones")
    args = ap.parse_args()

    jar_paths, mod_units = load_units(args)
    if args.javap:
        n, agreed, only_decoder, only_javap = javap_crosscheck(mod_units, args.javap, prefilter=not args.javap_all_classes)
        print(json.dumps({"classes_disassembled": n, "invokes_agreed": agreed,
                          "only_in_decoder": len(only_decoder), "only_in_javap": len(only_javap)}))
        for row in (only_decoder + only_javap)[:20]:
            print("MISMATCH", row)
        return 1 if (only_decoder or only_javap) else 0
    vanilla_units = [JarUnit("minecraft.jar", "minecraft", "26.1.2", zipfile.ZipFile(args.vanilla))]
    hierarchy = Hierarchy(vanilla_units + mod_units)

    stats = {"classes": 0, "parsed": 0, "errors": []}
    sites, rejected = scan_units(mod_units, hierarchy, stats)
    sites = dedupe(sites)
    unreasoned = {s.method_key for s in sites if not s.tick_driven}
    sites = apply_cross_class(sites, CrossClassResolver(mod_units, hierarchy, args.depth).resolve(unreasoned))
    vanilla_sites, _ = scan_units(vanilla_units, hierarchy, {"classes": 0, "parsed": 0, "errors": []})

    if args.csv:
        write_csv(args.csv, sites)
    table = per_mod_table(sites)
    print(json.dumps(summarize(len(jar_paths), len(mod_units), stats, sites, table, rejected, vanilla_sites), indent=1))
    cols = ["sites", "nav_impl", "navigation", "behavior_utils", "walk_target", "tick", "tick_same_or_direct",
            "entity", "tick_entity", "every_tick_entity", "every_tick_entity_unchecked", "every_tick_entity_no_throttle_hint", "every_tick_entity_in_loop", "every_tick_in_loop"]
    print("\nmod_id | " + " | ".join(cols))
    for mod, m in sorted(table.items(), key=lambda kv: (-kv[1]["every_tick_entity"], -kv[1]["tick_entity"], -kv[1]["tick"], kv[0])):
        print(mod + " | " + " | ".join(str(m[c]) for c in cols))
    print(f"\nrejected (request-named call, owner not navigation family), first {args.rejected}:")
    for r in rejected[: args.rejected]:
        print("  ", *r)
    for e in stats["errors"][:20]:
        print("PARSE ERROR", e, file=sys.stderr)
    return 1 if stats["errors"] else 0


if __name__ == "__main__":
    sys.exit(main())
