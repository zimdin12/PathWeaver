"""How much of a thread's sampled time is pathfinding, read from a local .sparkprofile.

Reads the file rather than an upload code, so the pack's composition never leaves the machine.

spark's call tree is FLATTENED: a ThreadNode carries a pool of StackTraceNode in `children` and the
actual tree shape in `children_refs`, which are indices into that pool. Treating `children` as the
thread's direct children -- which is the obvious reading, and the one that produced 0 ms everywhere
on the first attempt -- double counts the entire tree. The self-check below exists because of that:
if the roots do not sum to the thread total, the walk is wrong and the numbers are not printed.
"""
import sys
from pathlib import Path

sys.path.insert(0, r"C:\Users\Administrator\AppData\Roaming\.minecraft-dev\benchmarks\spark-proto-py")
from spark import spark_sampler_pb2  # noqa: E402

# Frames whose subtree is pathfinding. A* cost lands mostly in block lookups BELOW these frames, so
# attribution has to be by subtree, never by the self time of the named frame.
PATHFINDING = (
    "net.minecraft.world.level.pathfinder",
    "net.minecraft.world.entity.ai.navigation",
    "PathNavigation", "NodeEvaluator", "PathFinder", "PathfindingContext",
)
BRAIN_SINK = ("dev.pathweaver",)


def label(node):
    return f"{node.class_name}.{node.method_name}"


def total(node):
    return sum(node.times)


def summarise(path):
    data = spark_sampler_pb2.SamplerData()
    data.ParseFromString(Path(path).read_bytes())
    out = {}
    for thread in data.threads:
        pool = list(thread.children)
        thread_total = sum(thread.times)

        def subtree(i, inside_path, inside_pw):
            # Attribute the OUTERMOST matching frame's whole subtree, once. Adding a nested match on
            # top of the ancestor that already contains it counts the same samples twice, which is
            # how the pathweaver column first read 16836 ms of a 45000 ms thread.
            node = pool[i]
            name = label(node)
            is_path = inside_path or any(m in name for m in PATHFINDING)
            is_pw = inside_pw or any(m in name for m in BRAIN_SINK)
            path_time = total(node) if (is_path and not inside_path) else 0.0
            pw_time = total(node) if (is_pw and not inside_pw) else 0.0
            for c in node.children_refs:
                p, w = subtree(c, is_path, is_pw)
                if not is_path:
                    path_time += p
                if not is_pw:
                    pw_time += w
            return path_time, pw_time

        root_sum = sum(total(pool[i]) for i in thread.children_refs)
        path_time = pw_time = 0.0
        for i in thread.children_refs:
            p, w = subtree(i, False, False)
            path_time += p
            pw_time += w
        out[thread.name] = (thread_total, root_sum, path_time, pw_time)
    return out


def report(tag, path):
    print(f"===== {tag}   {Path(path).name}")
    for name, (thread_total, root_sum, path_time, pw_time) in summarise(path).items():
        if thread_total <= 0:
            continue
        drift = abs(root_sum - thread_total) / thread_total
        if drift > 0.01:
            print(f"  {name}: SELF-CHECK FAILED, roots sum to {root_sum:.0f} ms but the thread "
                  f"reports {thread_total:.0f} ms. The tree walk is wrong; no figure printed.")
            continue
        print(f"  {name:<28} total {thread_total:9.0f} ms   pathfinding {path_time:8.0f} ms "
              f"({100 * path_time / thread_total:5.2f}%)   pathweaver {pw_time:7.0f} ms")


if __name__ == "__main__":
    for arg in sys.argv[1:]:
        tag, _, p = arg.partition("=")
        report(tag if p else "", p or tag)
        print()
