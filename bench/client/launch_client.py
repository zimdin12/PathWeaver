"""Launch the live 317-mod pack client directly, without SKLauncher.

SKLauncher is a GUI, but the thing it produces is an ordinary `java` command: a classpath built from
the version manifests, a main class, and a handful of game arguments. Offline credentials are enough
because nothing here talks to a session server -- this loads a singleplayer world.

Writes the command to launch_cmd.txt and starts the process detached, so the caller can poll the log.
"""
import json
import os
import pathlib
import subprocess
import sys

MC = pathlib.Path(os.environ.get("PW_MC_WIN") or os.path.join(os.environ["APPDATA"], ".minecraft"))
VERSIONS = MC / "versions"
LIBS = MC / "libraries"
VERSION = "fabric-loader-0.19.3-26.1.2"
WORLD = sys.argv[2] if len(sys.argv) > 2 else "PW-repro"
JAVA = r"C:\Program Files\Eclipse Adoptium\jdk-25.0.3.9-hotspot\bin\javaw.exe"


def rules_allow(entry):
    """Honour the manifest's own os rules; a Linux-only native on the classpath is harmless but a
    disallowed one can shadow the right artifact."""
    rules = entry.get("rules")
    if not rules:
        return True
    allowed = False
    for rule in rules:
        os_spec = rule.get("os", {})
        name = os_spec.get("name")
        matches = name is None or name == "windows"
        if matches:
            allowed = rule.get("action") == "allow"
    return allowed


def maven_to_path(coord):
    parts = coord.split(":")
    group, artifact, version = parts[0], parts[1], parts[2]
    classifier = parts[3] if len(parts) > 3 else None
    name = f"{artifact}-{version}" + (f"-{classifier}" if classifier else "") + ".jar"
    return LIBS.joinpath(*group.split("."), artifact, version, name)


def collect(manifest, out):
    for lib in manifest.get("libraries", []):
        if not rules_allow(lib):
            continue
        # Natives are per-OS classpath entries here rather than a classifiers block. Other
        # platforms' natives are inert (different file names) but there is no reason to load them.
        name = lib.get("name", "")
        if "natives-" in name and "natives-windows" not in name:
            continue
        downloads = lib.get("downloads", {})
        artifact = downloads.get("artifact")
        if artifact and artifact.get("path"):
            out.append(LIBS / artifact["path"])
        elif "name" in lib:
            out.append(maven_to_path(lib["name"]))
        for cls in (downloads.get("classifiers") or {}).values():
            if cls.get("path") and "natives" in cls["path"]:
                out.append(LIBS / cls["path"])


fabric = json.load(open(VERSIONS / VERSION / f"{VERSION}.json", encoding="utf8"))
parent_id = fabric["inheritsFrom"]
vanilla = json.load(open(VERSIONS / parent_id / f"{parent_id}.json", encoding="utf8"))

cp = []
collect(fabric, cp)     # loader first, as the launcher orders it
collect(vanilla, cp)
cp.append(VERSIONS / parent_id / f"{parent_id}.jar")

missing = [str(p) for p in cp if not p.exists()]
seen, ordered = set(), []
for p in cp:
    if p.exists() and str(p) not in seen:
        seen.add(str(p))
        ordered.append(str(p))

print(f"classpath entries: {len(ordered)}  (missing/skipped: {len(missing)})")
for m in missing[:8]:
    print("  MISSING", m)

natives = MC / "bin" / "pw-natives"
natives.mkdir(parents=True, exist_ok=True)

jvm = [
    "-Xms4G", "-Xmx12G",
    "-XX:+UseZGC", "-XX:+AlwaysPreTouch", "-XX:+DisableExplicitGC", "-XX:+PerfDisableSharedMem",
    f"-Djava.library.path={natives}",
    f"-Dorg.lwjgl.librarypath={natives}",
    "-Dfml.ignoreInvalidMinecraftCertificates=true",
]
game = [
    "--username", "PWTester",
    "--version", VERSION,
    "--gameDir", str(MC),
    "--assetsDir", str(MC / "assets"),
    "--assetIndex", vanilla["assetIndex"]["id"],
    "--uuid", "0123456789abcdef0123456789abcdef",
    "--accessToken", "0",
    "--userType", "legacy",
    "--versionType", "release",
    "--width", "1280", "--height", "720",
    # Land straight in a world. Menu navigation by synthetic mouse clicks is coordinate-dependent
    # and breaks on any layout change; this is the launcher's own supported entry point.
    "--quickPlaySingleplayer", WORLD,
]

cmd = [JAVA, "-cp", os.pathsep.join(ordered), *jvm, fabric["mainClass"], *game]
pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else "launch_cmd.txt").write_text(
    "\n".join(cmd), encoding="utf8")

log = open(sys.argv[3] if len(sys.argv) > 3 else MC / "pw-client-launch.log", "wb")
proc = subprocess.Popen(cmd, cwd=str(MC), stdout=log, stderr=subprocess.STDOUT,
                        creationflags=subprocess.DETACHED_PROCESS | subprocess.CREATE_NEW_PROCESS_GROUP)
print(proc.pid)
