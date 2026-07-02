# PathWeaver Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build PathWeaver — a Fabric 26.1.2 mod that runs mob A* search off the main thread on a read-only snapshot (safe by construction) plus conservative repath elision, without duplicating or conflicting with Lithium.

**Architecture:** A `createPath` mixin (main thread) gates each request by exact `NodeEvaluator` class, snapshots the mob's inputs + a shared right-sized immutable `PathNavigationRegion`, and submits the A* search to a bounded worker pool. Completed `Path`s are installed at the next tick start after a staleness check; ineligible mobs fall through to unchanged vanilla sync pathing. A second, independently-toggleable feature widens vanilla's repath short-circuit to a tolerance.

**Tech Stack:** Java 25, Fabric Loom (no-remap, `fabric.loom.disableObfuscation=true`), Gradle 9.5, Fabric API, Cloth Config, MixinExtras, JUnit 5 (pure logic), Fabric GameTest (in-game equivalence/soak).

## Global Constraints

- Minecraft `>=26.1 <26.2`; Fabric loader; Java `>=25`.
- Build recipe (from `porting-26.1.2.md`): no-remap fabric-loom, `fabric.loom.disableObfuscation=true`, JDK 25 (`C:/Program Files/Eclipse Adoptium/jdk-25.0.3.9-hotspot`), Gradle 9.5.
- Hard deps only: `fabric-api`, `cloth-config`. No other runtime deps.
- **Never duplicate Lithium node-eval caching.** Coordinate mixin `@At`/priority with Lithium's `WalkNodeEvaluatorMixin`/`BlockStateBaseMixin`; leave neighbor/PathType evaluation to Lithium.
- **Worker threads touch ONLY the immutable snapshot.** Never read `ServerLevel.PathTypeCache` off-thread. Snapshot mob malus + capability flags at dispatch on the main thread.
- **Safety gate is exact-class (`getClass() ==`), never `instanceof`.** Default-deny.
- **Behavior fidelity is non-negotiable:** async A* must return a `Path` identical to sync A* on the same snapshot; repath elision must never skip a repath vanilla would genuinely require.
- Vanilla unobfuscated names are real in 26.1.2 (`net.minecraft.world.level.pathfinder.*`, `net.minecraft.world.entity.ai.navigation.*`).
- Commit after every green step. Package root `dev.pathweaver`.

---

## File Structure

- `settings.gradle`, `build.gradle`, `gradle.properties` — build (no-remap loom, JDK25).
- `src/main/resources/fabric.mod.json` — metadata, deps, entrypoints, mixin config.
- `src/main/resources/pathweaver.mixins.json` — mixin config (server-side).
- `src/main/java/dev/pathweaver/PathWeaver.java` — mod init (`ModInitializer`): wire pool lifecycle + foreign-mixin scan.
- `src/main/java/dev/pathweaver/config/PathWeaverConfig.java` — cloth-config model.
- `src/main/java/dev/pathweaver/gate/SafetyGate.java` — exact-class allowlist + foreign-mixin denial.
- `src/main/java/dev/pathweaver/gate/ForeignMixinScanner.java` — startup scan of loaded mixin configs.
- `src/main/java/dev/pathweaver/snapshot/SnapshotKey.java` — (dimension, tick, region-bounds) cache key.
- `src/main/java/dev/pathweaver/snapshot/SnapshotProvider.java` — per-tick right-sized shared immutable region cache.
- `src/main/java/dev/pathweaver/async/PathRequest.java` — immutable dispatch record (snapshot + captured mob inputs).
- `src/main/java/dev/pathweaver/async/PathWorkerPool.java` — bounded executor; runs `PathFinder.findPath`.
- `src/main/java/dev/pathweaver/async/ResultInstaller.java` — main-thread result drain + staleness check.
- `src/main/java/dev/pathweaver/mixin/PathNavigationMixin.java` — Feature A intercept + Feature B elision.
- `src/main/java/dev/pathweaver/mixin/MinecraftServerMixin.java` — tick hook to drain installer + clear snapshot cache.
- `src/test/java/dev/pathweaver/...` — JUnit logic tests.
- `src/gametest/java/dev/pathweaver/PathEquivalenceGameTest.java` — in-game async==sync + soak.

---

## Task 1: Buildable empty mod scaffold

**Files:**
- Create: `settings.gradle`, `build.gradle`, `gradle.properties`, `src/main/resources/fabric.mod.json`, `src/main/resources/pathweaver.mixins.json`, `src/main/java/dev/pathweaver/PathWeaver.java`

**Interfaces:**
- Produces: `dev.pathweaver.PathWeaver` (`ModInitializer` with `onInitialize()`); mod id `pathweaver`.

- [ ] **Step 1: Write `gradle.properties`**

```properties
org.gradle.jvmargs=-Xmx2G
minecraft_version=26.1.2
loader_version=0.19.3
fabric_version=*
mod_version=0.1.0
maven_group=dev.pathweaver
archives_base_name=pathweaver
fabric.loom.disableObfuscation=true
```

- [ ] **Step 2: Write `settings.gradle`**

```groovy
pluginManagement {
    repositories { maven { url = "https://maven.fabricmc.net/" }; gradlePluginPortal() }
}
```

- [ ] **Step 3: Write `build.gradle`** (no-remap loom + JDK25 toolchain + gametest source set)

```groovy
plugins { id 'fabric-loom' version '1.16.3'; id 'java' }
archivesBaseName = project.archives_base_name
version = project.mod_version
group = project.maven_group
repositories {
    maven { url = "https://maven.shedaniel.me/" } // cloth-config
    maven { url = "https://maven.terraformersmc.com/releases/" }
}
loom { runtimeOnlyLog4j = true }
dependencies {
    minecraft "com.mojang:minecraft:${project.minecraft_version}"
    mappings loom.layered {} // 26.1 unobfuscated: empty layered mappings
    modImplementation "net.fabricmc:fabric-loader:${project.loader_version}"
    modImplementation "net.fabricmc.fabric-api:fabric-api:${project.fabric_version}"
    modImplementation "me.shedaniel.cloth:cloth-config-fabric:+"
    testImplementation 'org.junit.jupiter:junit-jupiter:5.10.2'
}
java {
    toolchain { languageVersion = JavaLanguageVersion.of(25) }
    withSourcesJar()
}
test { useJUnitPlatform() }
processResources {
    inputs.property "version", project.version
    filesMatching("fabric.mod.json") { expand "version": project.version }
}
```

- [ ] **Step 4: Write `src/main/resources/fabric.mod.json`**

```json
{
  "schemaVersion": 1,
  "id": "pathweaver",
  "version": "${version}",
  "name": "PathWeaver",
  "description": "Async mob pathfinding on a read-only snapshot. Safe by construction.",
  "license": "MIT",
  "environment": "*",
  "entrypoints": { "main": ["dev.pathweaver.PathWeaver"] },
  "mixins": ["pathweaver.mixins.json"],
  "depends": {
    "minecraft": ">=26.1 <26.2",
    "java": ">=25",
    "fabricloader": "*",
    "fabric-api": "*",
    "cloth-config": "*"
  }
}
```

- [ ] **Step 5: Write `src/main/resources/pathweaver.mixins.json`**

```json
{
  "required": true,
  "package": "dev.pathweaver.mixin",
  "compatibilityLevel": "JAVA_21",
  "mixins": [],
  "client": [],
  "injectors": { "defaultRequire": 1 }
}
```

- [ ] **Step 6: Write `PathWeaver.java`**

```java
package dev.pathweaver;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PathWeaver implements ModInitializer {
    public static final String MOD_ID = "pathweaver";
    public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOG.info("PathWeaver initializing");
    }
}
```

- [ ] **Step 7: Build to verify scaffold compiles**

Run: `JAVA_HOME="C:/Program Files/Eclipse Adoptium/jdk-25.0.3.9-hotspot" ./gradlew build --no-daemon`
Expected: `BUILD SUCCESSFUL`; a `build/libs/pathweaver-0.1.0.jar` is produced.

- [ ] **Step 8: Commit**

```bash
git add settings.gradle build.gradle gradle.properties src/main/resources src/main/java/dev/pathweaver/PathWeaver.java
git commit -m "feat: buildable empty mod scaffold"
```

---

## Task 2: Config model (Cloth Config)

**Files:**
- Create: `src/main/java/dev/pathweaver/config/PathWeaverConfig.java`
- Test: `src/test/java/dev/pathweaver/config/PathWeaverConfigTest.java`

**Interfaces:**
- Produces: `PathWeaverConfig` with static `get()` returning a singleton; fields `boolean asyncEnabled` (default true), `boolean repathElisionEnabled` (default true), `int poolThreads` (default 0 = auto), `int maxInFlight` (default 256), `boolean distanceThrottleEnabled` (default false), `boolean syncFallbackOnly` (default false); method `int resolvedPoolThreads()` returning `poolThreads>0 ? poolThreads : Math.max(1, Runtime.getRuntime().availableProcessors()/4)`.

- [ ] **Step 1: Write the failing test**

```java
package dev.pathweaver.config;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PathWeaverConfigTest {
    @Test void autoPoolThreadsClampsToAtLeastOne() {
        PathWeaverConfig c = new PathWeaverConfig();
        c.poolThreads = 0;
        assertTrue(c.resolvedPoolThreads() >= 1);
    }
    @Test void explicitPoolThreadsHonored() {
        PathWeaverConfig c = new PathWeaverConfig();
        c.poolThreads = 3;
        assertEquals(3, c.resolvedPoolThreads());
    }
    @Test void defaultsAreConservative() {
        PathWeaverConfig c = new PathWeaverConfig();
        assertTrue(c.asyncEnabled);
        assertTrue(c.repathElisionEnabled);
        assertFalse(c.distanceThrottleEnabled);
        assertFalse(c.syncFallbackOnly);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests '*PathWeaverConfigTest*'`
Expected: FAIL — `PathWeaverConfig` not found.

- [ ] **Step 3: Write `PathWeaverConfig.java`**

```java
package dev.pathweaver.config;

public class PathWeaverConfig {
    public boolean asyncEnabled = true;
    public boolean repathElisionEnabled = true;
    public int poolThreads = 0;          // 0 = auto
    public int maxInFlight = 256;
    public boolean distanceThrottleEnabled = false;
    public boolean syncFallbackOnly = false;

    private static PathWeaverConfig INSTANCE = new PathWeaverConfig();
    public static PathWeaverConfig get() { return INSTANCE; }
    public static void set(PathWeaverConfig c) { INSTANCE = c; }

    public int resolvedPoolThreads() {
        return poolThreads > 0 ? poolThreads
             : Math.max(1, Runtime.getRuntime().availableProcessors() / 4);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests '*PathWeaverConfigTest*'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dev/pathweaver/config src/test/java/dev/pathweaver/config
git commit -m "feat: config model with conservative defaults"
```

> Cloth-config GUI screen wiring (Modrinth-visible config) is deferred to Task 11 (polish) — the model above is what the engine reads; the GUI is presentation.

---

## Task 3: SafetyGate — exact-class allowlist

**Files:**
- Create: `src/main/java/dev/pathweaver/gate/SafetyGate.java`
- Test: `src/test/java/dev/pathweaver/gate/SafetyGateTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `SafetyGate` with static `boolean isEvaluatorAllowed(Class<?> evaluatorClass)`; a mutable static `java.util.Set<Class<?>> deniedBySafety` (families forced sync by the foreign-mixin scan, Task 4) and `boolean isAllowed(Class<?> evaluatorClass)` that combines allowlist AND `deniedBySafety` emptiness for the vanilla classes. The allowlist is the exact classes `WalkNodeEvaluator`, `SwimNodeEvaluator`, `AmphibiousNodeEvaluator`, `FlyNodeEvaluator`.

- [ ] **Step 1: Write the failing test**

```java
package dev.pathweaver.gate;
import net.minecraft.world.level.pathfinder.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SafetyGateTest {
    static class FakeSpiderEvaluator extends WalkNodeEvaluator {}

    @Test void vanillaWalkAllowed() {
        assertTrue(SafetyGate.isEvaluatorAllowed(WalkNodeEvaluator.class));
    }
    @Test void subclassDeniedByExactClass() {
        // instanceof would wrongly pass this; exact-class must deny it
        assertFalse(SafetyGate.isEvaluatorAllowed(FakeSpiderEvaluator.class));
    }
    @Test void unknownEvaluatorDenied() {
        assertFalse(SafetyGate.isEvaluatorAllowed(Object.class));
    }
    @Test void swimAndAmphibiousAndFlyAllowed() {
        assertTrue(SafetyGate.isEvaluatorAllowed(SwimNodeEvaluator.class));
        assertTrue(SafetyGate.isEvaluatorAllowed(AmphibiousNodeEvaluator.class));
        assertTrue(SafetyGate.isEvaluatorAllowed(FlyNodeEvaluator.class));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests '*SafetyGateTest*'`
Expected: FAIL — `SafetyGate` not found.

- [ ] **Step 3: Write `SafetyGate.java`**

```java
package dev.pathweaver.gate;

import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import net.minecraft.world.level.pathfinder.SwimNodeEvaluator;
import net.minecraft.world.level.pathfinder.AmphibiousNodeEvaluator;
import net.minecraft.world.level.pathfinder.FlyNodeEvaluator;
import java.util.Set;
import java.util.Collections;
import java.util.HashSet;

public final class SafetyGate {
    private static final Set<Class<?>> ALLOWED = Set.of(
        WalkNodeEvaluator.class,
        SwimNodeEvaluator.class,
        AmphibiousNodeEvaluator.class,
        FlyNodeEvaluator.class
    );
    // Populated by ForeignMixinScanner: allowlisted classes another jar mixed into.
    public static final Set<Class<?>> deniedBySafety =
        Collections.synchronizedSet(new HashSet<>());

    private SafetyGate() {}

    public static boolean isEvaluatorAllowed(Class<?> evaluatorClass) {
        return ALLOWED.contains(evaluatorClass); // exact-class, NOT instanceof
    }

    /** Full gate: allowlisted AND not force-denied by a foreign mixin. */
    public static boolean isAllowed(Class<?> evaluatorClass) {
        return isEvaluatorAllowed(evaluatorClass)
            && !deniedBySafety.contains(evaluatorClass);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests '*SafetyGateTest*'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dev/pathweaver/gate/SafetyGate.java src/test/java/dev/pathweaver/gate/SafetyGateTest.java
git commit -m "feat: exact-class safety gate for node evaluators"
```

---

## Task 4: ForeignMixinScanner — deny allowlisted classes other mods mix into

**Files:**
- Create: `src/main/java/dev/pathweaver/gate/ForeignMixinScanner.java`
- Modify: `src/main/java/dev/pathweaver/PathWeaver.java` (call scanner in `onInitialize`)
- Test: `src/test/java/dev/pathweaver/gate/ForeignMixinScannerTest.java`

**Interfaces:**
- Consumes: `SafetyGate.deniedBySafety`.
- Produces: `ForeignMixinScanner.scanAndPopulate()` — iterates Fabric's loaded mod list, reads each mod's declared mixin config resources, and for any mixin whose `target` resolves to one of the allowlisted vanilla classes (from a jar other than `pathweaver`), adds that class to `SafetyGate.deniedBySafety` and logs a warning. Also exposes pure helper `static Set<Class<?>> targetsTouchingAllowlist(Collection<String> mixinTargetClassNames)` for unit testing without the mod environment.

- [ ] **Step 1: Write the failing test** (pure helper, no game env)

```java
package dev.pathweaver.gate;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class ForeignMixinScannerTest {
    @Test void detectsMixinIntoAllowlistedClass() {
        Set<Class<?>> hit = ForeignMixinScanner.targetsTouchingAllowlist(
            List.of("net.minecraft.world.level.pathfinder.WalkNodeEvaluator"));
        assertTrue(hit.contains(net.minecraft.world.level.pathfinder.WalkNodeEvaluator.class));
    }
    @Test void ignoresUnrelatedTargets() {
        Set<Class<?>> hit = ForeignMixinScanner.targetsTouchingAllowlist(
            List.of("net.minecraft.world.entity.Mob"));
        assertTrue(hit.isEmpty());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests '*ForeignMixinScannerTest*'`
Expected: FAIL — `ForeignMixinScanner` not found.

- [ ] **Step 3: Write `ForeignMixinScanner.java`**

```java
package dev.pathweaver.gate;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import net.minecraft.world.level.pathfinder.SwimNodeEvaluator;
import net.minecraft.world.level.pathfinder.AmphibiousNodeEvaluator;
import net.minecraft.world.level.pathfinder.FlyNodeEvaluator;
import dev.pathweaver.PathWeaver;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public final class ForeignMixinScanner {
    private static final Map<String, Class<?>> ALLOWLISTED_BY_NAME = Map.of(
        WalkNodeEvaluator.class.getName(), WalkNodeEvaluator.class,
        SwimNodeEvaluator.class.getName(), SwimNodeEvaluator.class,
        AmphibiousNodeEvaluator.class.getName(), AmphibiousNodeEvaluator.class,
        FlyNodeEvaluator.class.getName(), FlyNodeEvaluator.class
    );
    // Also treat PathFinder as a sensitive target (mixin here can bias the search).
    private static final Set<String> SENSITIVE_UNGATED = Set.of(
        "net.minecraft.world.level.pathfinder.PathFinder"
    );

    private ForeignMixinScanner() {}

    /** Pure, testable: map fully-qualified mixin target names to allowlisted classes hit. */
    public static Set<Class<?>> targetsTouchingAllowlist(Collection<String> targetClassNames) {
        Set<Class<?>> hits = new HashSet<>();
        for (String t : targetClassNames) {
            Class<?> c = ALLOWLISTED_BY_NAME.get(t);
            if (c != null) hits.add(c);
        }
        return hits;
    }

    /** Environment scan: walk every non-pathweaver mod's mixin configs, deny hits. */
    public static void scanAndPopulate() {
        boolean sensitiveHit = false;
        for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
            String id = mod.getMetadata().getId();
            if (id.equals(PathWeaver.MOD_ID)) continue;
            for (String cfgName : declaredMixinConfigs(mod)) {
                Collection<String> targets = readMixinTargets(mod, cfgName);
                Set<Class<?>> hits = targetsTouchingAllowlist(targets);
                for (Class<?> c : hits) {
                    SafetyGate.deniedBySafety.add(c);
                    PathWeaver.LOG.warn("Mod '{}' mixes into {}; forcing that family sync.",
                        id, c.getSimpleName());
                }
                for (String t : targets) if (SENSITIVE_UNGATED.contains(t)) {
                    sensitiveHit = true;
                    PathWeaver.LOG.warn("Mod '{}' mixes into PathFinder; async paths may be affected.", id);
                }
            }
        }
        if (sensitiveHit) PathWeaver.LOG.warn("A mod targets PathFinder — verify path fidelity in-game.");
    }

    private static List<String> declaredMixinConfigs(ModContainer mod) {
        // Fabric exposes custom "mixins" in fabric.mod.json; fall back to scanning root *.mixins.json.
        List<String> out = new ArrayList<>();
        Object custom = mod.getMetadata().getCustomValue("fabric-loom:mixin");
        // Robust path: enumerate root resources ending in .mixins.json
        for (Path root : mod.getRootPaths()) {
            try (var s = Files.list(root)) {
                s.filter(p -> p.getFileName().toString().endsWith("mixins.json"))
                 .forEach(p -> out.add(p.getFileName().toString()));
            } catch (Exception ignored) {}
        }
        return out;
    }

    private static Collection<String> readMixinTargets(ModContainer mod, String cfgName) {
        Set<String> targets = new HashSet<>();
        for (Path root : mod.getRootPaths()) {
            Path cfg = root.resolve(cfgName);
            if (!Files.exists(cfg)) continue;
            try (InputStream in = Files.newInputStream(cfg)) {
                JsonObject o = JsonParser.parseReader(new InputStreamReader(in)).getAsJsonObject();
                String pkg = o.has("package") ? o.get("package").getAsString() : "";
                for (String key : List.of("mixins", "client", "server")) {
                    if (!o.has(key)) continue;
                    o.getAsJsonArray(key).forEach(e -> {
                        // Mixin class name -> read its @Mixin target via simple heuristic:
                        // we cannot resolve @Mixin annotations from JSON, so we record the
                        // mixin's own package+name and resolve targets at class-load via ASM.
                        // For the JSON pass we conservatively map by resource; ASM pass in scanAsm().
                    });
                }
            } catch (Exception ignored) {}
        }
        targets.addAll(scanAsmTargets(mod, cfgName));
        return targets;
    }

    /** Read @Mixin(value=...) targets from the mixin classes via ASM (no class init). */
    private static Collection<String> scanAsmTargets(ModContainer mod, String cfgName) {
        // Implemented in Task 4b if JSON-only proves insufficient. Vanilla-target mixins
        // usually declare targets as class refs; ASM reads the annotation without loading.
        return List.of();
    }
}
```

> **Note for implementer:** the JSON pass alone cannot see `@Mixin(WalkNodeEvaluator.class)` targets (they're annotations on the mixin classes, not in the JSON). The pure helper `targetsTouchingAllowlist` is unit-tested now; the ASM annotation read is finished in Task 4b. Ship Task 4 with the helper + environment plumbing; the ASM reader is the deliverable of 4b.

- [ ] **Step 4: Wire into `PathWeaver.onInitialize`**

```java
// in onInitialize(), after LOG.info(...)
dev.pathweaver.gate.ForeignMixinScanner.scanAndPopulate();
```

- [ ] **Step 5: Run tests**

Run: `./gradlew test --tests '*ForeignMixinScannerTest*'`
Expected: PASS (helper-level).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/dev/pathweaver/gate/ForeignMixinScanner.java src/main/java/dev/pathweaver/PathWeaver.java src/test/java/dev/pathweaver/gate/ForeignMixinScannerTest.java
git commit -m "feat: foreign-mixin scanner denies allowlisted classes other mods patch"
```

---

## Task 4b: ASM annotation reader for mixin targets

**Files:**
- Modify: `src/main/java/dev/pathweaver/gate/ForeignMixinScanner.java` (`scanAsmTargets`)
- Test: `src/test/java/dev/pathweaver/gate/AsmTargetReaderTest.java`

**Interfaces:**
- Produces: `static List<String> readMixinAnnotationTargets(byte[] classBytes)` returning fully-qualified target class names found in `@Mixin` on the class (from `value` class array and `targets` string array).

- [ ] **Step 1: Failing test** — compile a tiny mixin-annotated class fixture into bytes and assert target extraction.

```java
package dev.pathweaver.gate;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class AsmTargetReaderTest {
    // Uses a precompiled fixture resource: src/test/resources/FixtureMixin.class
    // annotated @Mixin(targets = "net.minecraft.world.level.pathfinder.WalkNodeEvaluator")
    @Test void readsStringTargets() throws Exception {
        byte[] bytes = getClass().getResourceAsStream("/FixtureMixin.class").readAllBytes();
        List<String> t = ForeignMixinScanner.readMixinAnnotationTargets(bytes);
        assertTrue(t.contains("net.minecraft.world.level.pathfinder.WalkNodeEvaluator"));
    }
}
```

- [ ] **Step 2: Run to verify fail**

Run: `./gradlew test --tests '*AsmTargetReaderTest*'`
Expected: FAIL — method missing.

- [ ] **Step 3: Implement ASM reader** (Loom bundles ASM via mixin)

```java
// add to ForeignMixinScanner
public static java.util.List<String> readMixinAnnotationTargets(byte[] classBytes) {
    java.util.List<String> out = new java.util.ArrayList<>();
    org.objectweb.asm.ClassReader cr = new org.objectweb.asm.ClassReader(classBytes);
    cr.accept(new org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9) {
        @Override public org.objectweb.asm.AnnotationVisitor visitAnnotation(String desc, boolean vis) {
            if (!desc.endsWith("Lorg/spongepowered/asm/mixin/Mixin;")) return null;
            return new org.objectweb.asm.AnnotationVisitor(org.objectweb.asm.Opcodes.ASM9) {
                @Override public org.objectweb.asm.AnnotationVisitor visitArray(String name) {
                    return new org.objectweb.asm.AnnotationVisitor(org.objectweb.asm.Opcodes.ASM9) {
                        @Override public void visit(String n, Object value) {
                            if (value instanceof org.objectweb.asm.Type t) out.add(t.getClassName());
                            else if (value instanceof String s) out.add(s);
                        }
                    };
                }
            };
        }
    }, org.objectweb.asm.ClassReader.SKIP_CODE);
    return out;
}
```

Then make `scanAsmTargets` read each mixin class listed in the JSON config from the mod's root paths and call `readMixinAnnotationTargets`.

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew test --tests '*AsmTargetReaderTest*'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dev/pathweaver/gate/ForeignMixinScanner.java src/test/java/dev/pathweaver/gate/AsmTargetReaderTest.java src/test/resources/FixtureMixin.class
git commit -m "feat: ASM reader extracts @Mixin targets for foreign-mixin detection"
```

---

## Task 5: SnapshotProvider — right-sized shared immutable region

**Files:**
- Create: `src/main/java/dev/pathweaver/snapshot/SnapshotKey.java`, `src/main/java/dev/pathweaver/snapshot/SnapshotProvider.java`
- Test: `src/test/java/dev/pathweaver/snapshot/SnapshotProviderTest.java`

**Interfaces:**
- Consumes: nothing (pure geometry + caching; region construction is injected via a functional factory so it's unit-testable without a world).
- Produces:
  - `SnapshotKey(String dimensionId, long tick, int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ)` (record).
  - `SnapshotProvider` with `int rightSizedRadius(int vanillaRadius, int actualMaxPathLength, int margin)` = `Math.max(actualMaxPathLength + margin, 1)` bounded above by `vanillaRadius`; `<R> R getOrBuild(SnapshotKey key, java.util.function.Supplier<R> factory)` returning a cached value per key; `void clearTick(long tick)` dropping entries not matching the current tick.

- [ ] **Step 1: Failing test**

```java
package dev.pathweaver.snapshot;
import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class SnapshotProviderTest {
    @Test void radiusNeverExceedsVanillaNorGoesBelowPathLength() {
        SnapshotProvider p = new SnapshotProvider();
        assertEquals(10, p.rightSizedRadius(32, 8, 2));   // 8+2=10 < 32
        assertEquals(32, p.rightSizedRadius(32, 40, 2));  // clamp to vanilla 32
        assertEquals(1, p.rightSizedRadius(32, 0, 0));    // floor 1
    }
    @Test void sharesOneBuildPerKeyInSameTick() {
        SnapshotProvider p = new SnapshotProvider();
        SnapshotKey k = new SnapshotKey("minecraft:overworld", 100L, 0,0,2,2);
        AtomicInteger builds = new AtomicInteger();
        Object a = p.getOrBuild(k, () -> { builds.incrementAndGet(); return new Object(); });
        Object b = p.getOrBuild(k, () -> { builds.incrementAndGet(); return new Object(); });
        assertSame(a, b);
        assertEquals(1, builds.get());
    }
    @Test void clearTickDropsStaleEntries() {
        SnapshotProvider p = new SnapshotProvider();
        SnapshotKey k = new SnapshotKey("minecraft:overworld", 100L, 0,0,2,2);
        p.getOrBuild(k, Object::new);
        p.clearTick(101L); // different tick -> evict
        AtomicInteger builds = new AtomicInteger();
        p.getOrBuild(k, () -> { builds.incrementAndGet(); return new Object(); });
        assertEquals(1, builds.get()); // rebuilt because evicted
    }
}
```

- [ ] **Step 2: Run to verify fail**

Run: `./gradlew test --tests '*SnapshotProviderTest*'`
Expected: FAIL — classes not found.

- [ ] **Step 3: Implement `SnapshotKey` + `SnapshotProvider`**

```java
// SnapshotKey.java
package dev.pathweaver.snapshot;
public record SnapshotKey(String dimensionId, long tick,
                          int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ) {}
```

```java
// SnapshotProvider.java
package dev.pathweaver.snapshot;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

public class SnapshotProvider {
    private final Map<SnapshotKey, Object> cache = new ConcurrentHashMap<>();

    public int rightSizedRadius(int vanillaRadius, int actualMaxPathLength, int margin) {
        int wanted = Math.max(actualMaxPathLength + margin, 1);
        return Math.min(wanted, vanillaRadius);
    }

    @SuppressWarnings("unchecked")
    public <R> R getOrBuild(SnapshotKey key, Supplier<R> factory) {
        return (R) cache.computeIfAbsent(key, k -> factory.get());
    }

    public void clearTick(long currentTick) {
        cache.keySet().removeIf(k -> k.tick() != currentTick);
    }
}
```

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew test --tests '*SnapshotProviderTest*'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dev/pathweaver/snapshot src/test/java/dev/pathweaver/snapshot
git commit -m "feat: right-sized shared per-tick snapshot cache"
```

---

## Task 6: PathRequest + PathWorkerPool

**Files:**
- Create: `src/main/java/dev/pathweaver/async/PathRequest.java`, `src/main/java/dev/pathweaver/async/PathWorkerPool.java`
- Test: `src/test/java/dev/pathweaver/async/PathWorkerPoolTest.java`

**Interfaces:**
- Consumes: `PathWeaverConfig`.
- Produces:
  - `PathRequest` — a record/functional holder carrying a `java.util.concurrent.Callable<net.minecraft.world.level.pathfinder.Path>` `search`, plus opaque identity fields `int entityId`, `long dispatchTick`, and a `java.util.function.Consumer<net.minecraft.world.level.pathfinder.Path> onDone` (invoked on the pool thread only to enqueue the result — never to touch the world).
  - `PathWorkerPool` with `void start(int threads, int maxInFlight)`, `boolean submit(PathRequest req)` (returns false if in-flight cap hit → caller does sync), `int inFlight()`, `void shutdown()`. On task exception it logs once and completes with `null` (caller treats null as "fall back to sync next time").

- [ ] **Step 1: Failing test** (pool runs the callable off the calling thread and reports completion)

```java
package dev.pathweaver.async;
import org.junit.jupiter.api.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class PathWorkerPoolTest {
    PathWorkerPool pool;
    @BeforeEach void up() { pool = new PathWorkerPool(); pool.start(2, 4); }
    @AfterEach void down() { pool.shutdown(); }

    @Test void runsOffCallingThreadAndCompletes() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Thread> ran = new AtomicReference<>();
        PathRequest req = new PathRequest(1, 0L,
            () -> { ran.set(Thread.currentThread()); return null; },
            p -> done.countDown());
        assertTrue(pool.submit(req));
        assertTrue(done.await(2, TimeUnit.SECONDS));
        assertNotSame(Thread.currentThread(), ran.get());
    }

    @Test void inFlightCapRejects() {
        // saturate with blocking tasks
        CountDownLatch block = new CountDownLatch(1);
        for (int i = 0; i < 4; i++) {
            pool.submit(new PathRequest(i, 0L, () -> { block.await(); return null; }, p -> {}));
        }
        boolean accepted = pool.submit(new PathRequest(99, 0L, () -> null, p -> {}));
        assertFalse(accepted); // cap hit
        block.countDown();
    }

    @Test void taskExceptionCompletesWithNull() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Object> got = new AtomicReference<>("sentinel");
        pool.submit(new PathRequest(2, 0L,
            () -> { throw new RuntimeException("boom"); },
            p -> { got.set(p); done.countDown(); }));
        assertTrue(done.await(2, TimeUnit.SECONDS));
        assertNull(got.get());
    }
}
```

- [ ] **Step 2: Run to verify fail**

Run: `./gradlew test --tests '*PathWorkerPoolTest*'`
Expected: FAIL — classes not found.

- [ ] **Step 3: Implement `PathRequest` + `PathWorkerPool`**

```java
// PathRequest.java
package dev.pathweaver.async;
import net.minecraft.world.level.pathfinder.Path;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

public record PathRequest(int entityId, long dispatchTick,
                          Callable<Path> search, Consumer<Path> onDone) {}
```

```java
// PathWorkerPool.java
package dev.pathweaver.async;

import dev.pathweaver.PathWeaver;
import net.minecraft.world.level.pathfinder.Path;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class PathWorkerPool {
    private ThreadPoolExecutor exec;
    private final AtomicInteger inFlight = new AtomicInteger();
    private int maxInFlight;

    public void start(int threads, int maxInFlight) {
        this.maxInFlight = maxInFlight;
        this.exec = new ThreadPoolExecutor(threads, threads, 30, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(),
            r -> { Thread t = new Thread(r, "PathWeaver-Worker");
                   t.setDaemon(true); t.setPriority(Thread.NORM_PRIORITY - 1); return t; });
        this.exec.allowCoreThreadTimeOut(true);
    }

    public boolean submit(PathRequest req) {
        if (inFlight.get() >= maxInFlight) return false;
        inFlight.incrementAndGet();
        try {
            exec.execute(() -> {
                Path result = null;
                try { result = req.search().call(); }
                catch (Throwable t) {
                    PathWeaver.LOG.warn("Async path search failed; falling back to sync.", t);
                    result = null;
                } finally {
                    inFlight.decrementAndGet();
                    try { req.onDone().accept(result); } catch (Throwable ignored) {}
                }
            });
            return true;
        } catch (RejectedExecutionException e) {
            inFlight.decrementAndGet();
            return false;
        }
    }

    public int inFlight() { return inFlight.get(); }
    public void shutdown() { if (exec != null) exec.shutdownNow(); }
}
```

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew test --tests '*PathWorkerPoolTest*'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dev/pathweaver/async src/test/java/dev/pathweaver/async
git commit -m "feat: bounded path worker pool with sync-fallback on saturation/exception"
```

---

## Task 7: ResultInstaller — main-thread drain + staleness

**Files:**
- Create: `src/main/java/dev/pathweaver/async/ResultInstaller.java`
- Test: `src/test/java/dev/pathweaver/async/ResultInstallerTest.java`

**Interfaces:**
- Consumes: `PathRequest` identity fields.
- Produces:
  - `ResultInstaller` with `void enqueue(int entityId, long dispatchTick, Path path, double dispatchX, double dispatchY, double dispatchZ)` (thread-safe, called from pool thread), and `void drain(InstallSink sink)` (main thread) that pops all completed results and calls `sink.install(...)` only when not stale.
  - Interface `InstallSink { boolean isStale(int entityId, long dispatchTick, double dispatchX, double dispatchY, double dispatchZ); void install(int entityId, Path path); void discard(int entityId); }`.
  - Staleness is decided by the sink (needs live entity), but the installer guarantees ordering and one-shot delivery.

- [ ] **Step 1: Failing test** (uses a fake sink; no world needed)

```java
package dev.pathweaver.async;
import net.minecraft.world.level.pathfinder.Path;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ResultInstallerTest {
    static class FakeSink implements ResultInstaller.InstallSink {
        final Set<Integer> stale; final List<Integer> installed = new ArrayList<>();
        final List<Integer> discarded = new ArrayList<>();
        FakeSink(Set<Integer> stale) { this.stale = stale; }
        public boolean isStale(int id, long t, double x,double y,double z){ return stale.contains(id); }
        public void install(int id, Path p){ installed.add(id); }
        public void discard(int id){ discarded.add(id); }
    }
    @Test void installsFreshDiscardsStale() {
        ResultInstaller r = new ResultInstaller();
        r.enqueue(1, 0L, null, 0,0,0);
        r.enqueue(2, 0L, null, 0,0,0);
        FakeSink sink = new FakeSink(Set.of(2));
        r.drain(sink);
        assertEquals(List.of(1), sink.installed);
        assertEquals(List.of(2), sink.discarded);
    }
    @Test void drainDeliversEachResultOnce() {
        ResultInstaller r = new ResultInstaller();
        r.enqueue(1, 0L, null, 0,0,0);
        FakeSink sink = new FakeSink(Set.of());
        r.drain(sink);
        r.drain(sink); // nothing left
        assertEquals(List.of(1), sink.installed);
    }
}
```

- [ ] **Step 2: Run to verify fail**

Run: `./gradlew test --tests '*ResultInstallerTest*'`
Expected: FAIL — class not found.

- [ ] **Step 3: Implement `ResultInstaller`**

```java
package dev.pathweaver.async;

import net.minecraft.world.level.pathfinder.Path;
import java.util.concurrent.ConcurrentLinkedQueue;

public class ResultInstaller {
    public interface InstallSink {
        boolean isStale(int entityId, long dispatchTick, double x, double y, double z);
        void install(int entityId, Path path);
        void discard(int entityId);
    }
    private record Result(int entityId, long dispatchTick, Path path, double x, double y, double z) {}
    private final ConcurrentLinkedQueue<Result> queue = new ConcurrentLinkedQueue<>();

    public void enqueue(int entityId, long dispatchTick, Path path, double x, double y, double z) {
        queue.add(new Result(entityId, dispatchTick, path, x, y, z));
    }

    public void drain(InstallSink sink) {
        Result r;
        while ((r = queue.poll()) != null) {
            if (r.path() == null) { sink.discard(r.entityId()); continue; }
            if (sink.isStale(r.entityId(), r.dispatchTick(), r.x(), r.y(), r.z())) {
                sink.discard(r.entityId());
            } else {
                sink.install(r.entityId(), r.path());
            }
        }
    }
}
```

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew test --tests '*ResultInstallerTest*'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dev/pathweaver/async/ResultInstaller.java src/test/java/dev/pathweaver/async/ResultInstallerTest.java
git commit -m "feat: main-thread result installer with sink-decided staleness"
```

---

## Task 8: Runtime wiring — pool lifecycle + per-tick drain/clear

**Files:**
- Create: `src/main/java/dev/pathweaver/PathWeaverRuntime.java`, `src/main/java/dev/pathweaver/mixin/MinecraftServerMixin.java`
- Modify: `src/main/resources/pathweaver.mixins.json` (add `MinecraftServerMixin`), `PathWeaver.java`

**Interfaces:**
- Consumes: `PathWorkerPool`, `ResultInstaller`, `SnapshotProvider`, `PathWeaverConfig`.
- Produces: `PathWeaverRuntime` singleton exposing `PathWorkerPool pool()`, `ResultInstaller installer()`, `SnapshotProvider snapshots()`, `void onServerStarting(server)`, `void onServerStopping()`, `void onEndTick(server)` — the last drains the installer (using the global `EntityInstallSink`, Task 9) and calls `snapshots().clearTick(currentTick)`.

- [ ] **Step 1:** Write `PathWeaverRuntime` holding the three services; `onServerStarting` calls `pool().start(config.resolvedPoolThreads(), config.maxInFlight)`; `onServerStopping` calls `pool().shutdown()`. (No unit test — this is lifecycle glue verified by the gametest in Task 11.)

```java
package dev.pathweaver;

import dev.pathweaver.async.*;
import dev.pathweaver.config.PathWeaverConfig;
import dev.pathweaver.snapshot.SnapshotProvider;
import net.minecraft.server.MinecraftServer;

public final class PathWeaverRuntime {
    private static final PathWeaverRuntime INSTANCE = new PathWeaverRuntime();
    public static PathWeaverRuntime get() { return INSTANCE; }

    private final PathWorkerPool pool = new PathWorkerPool();
    private final ResultInstaller installer = new ResultInstaller();
    private final SnapshotProvider snapshots = new SnapshotProvider();
    private ResultInstaller.InstallSink sink;

    public PathWorkerPool pool() { return pool; }
    public ResultInstaller installer() { return installer; }
    public SnapshotProvider snapshots() { return snapshots; }
    public void setSink(ResultInstaller.InstallSink s) { this.sink = s; }

    public void onServerStarting(MinecraftServer server) {
        PathWeaverConfig c = PathWeaverConfig.get();
        pool.start(c.resolvedPoolThreads(), c.maxInFlight);
    }
    public void onServerStopping() { pool.shutdown(); }

    public void onEndTick(MinecraftServer server) {
        if (sink != null) installer.drain(sink);
        long tick = server.getTickCount();
        snapshots.clearTick(tick);
    }
}
```

- [ ] **Step 2:** Register server lifecycle events in `PathWeaver.onInitialize` using Fabric's `ServerLifecycleEvents` + `ServerTickEvents.END_SERVER_TICK`.

```java
// in onInitialize()
net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTING
    .register(s -> PathWeaverRuntime.get().onServerStarting(s));
net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPING
    .register(s -> PathWeaverRuntime.get().onServerStopping());
net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK
    .register(s -> PathWeaverRuntime.get().onEndTick(s));
```

- [ ] **Step 3:** Build to verify wiring compiles.

Run: `JAVA_HOME="C:/Program Files/Eclipse Adoptium/jdk-25.0.3.9-hotspot" ./gradlew build --no-daemon`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/dev/pathweaver/PathWeaverRuntime.java src/main/java/dev/pathweaver/PathWeaver.java
git commit -m "feat: runtime services + server lifecycle/tick wiring"
```

> `MinecraftServerMixin` is unused if Fabric tick events suffice; keep the event-based approach and drop the mixin file reference. (Decision: use Fabric events, no server mixin.)

---

## Task 9: Feature A — the createPath interceptor + EntityInstallSink

**Files:**
- Create: `src/main/java/dev/pathweaver/mixin/PathNavigationMixin.java`, `src/main/java/dev/pathweaver/async/EntityInstallSink.java`
- Modify: `pathweaver.mixins.json` (add `PathNavigationMixin`)

**Interfaces:**
- Consumes: `SafetyGate.isAllowed`, `PathWeaverRuntime`, vanilla `PathNavigation` internals (`this.mob`, `this.nodeEvaluator`, `this.pathFinder`, `createPath(Set<BlockPos>, int, boolean, int, float)`).
- Produces: async dispatch behavior; `EntityInstallSink` implementing `ResultInstaller.InstallSink` against the live `ServerLevel` (resolve entity by id; `isStale` = entity gone, or moved > `config`-threshold from dispatch pos, or target changed; `install` = set the mob's navigation path; `discard` = no-op/flag re-request).

**Design notes for implementer (read before coding):**
- Vanilla `PathNavigation.createPath(Set<BlockPos> targets, ...)` builds a `PathNavigationRegion`, constructs a `PathFinder`, and returns a `Path`. We `@Inject` at `HEAD` with `cancellable=true`. If `SafetyGate.isAllowed(this.nodeEvaluator.getClass())` AND `config.asyncEnabled` AND `!config.syncFallbackOnly`:
  1. On the main thread, build the right-sized region (via `SnapshotProvider`) and capture all A* inputs + mob malus/capability flags into locals (NOT references to the live mob).
  2. Submit a `PathRequest` whose `search` runs `pathFinder.findPath(region, mob, targets, followRange, distance, multiplier)` — but `mob` must not be dereferenced for live state inside; if vanilla `findPath` reads only evaluator+region this is safe (validated by the safety audit for allowlisted classes). If any needed value comes from the mob, capture it beforehand and pass a lightweight context.
  3. Set the injection's return value to the mob's **current** path (or `null`/empty so the mob keeps moving) and `ci.cancel()` — the real path installs next tick.
- `onDone` (pool thread) calls `runtime.installer().enqueue(entityId, tick, path, x, y, z)`. **Never touches the world.**
- **Fidelity guard:** the async `search` must call the *same* `pathFinder.findPath(...)` overload with the *same* args vanilla would, against a region that is a superset-or-equal of vanilla's for the requested targets. The equivalence gametest (Task 11) is the proof.

- [ ] **Step 1:** Write `EntityInstallSink` (resolve by id via `server.getLevel(...).getEntity(id)`; threshold from config; install by calling the navigation's path-set method used by vanilla when a path is computed).

- [ ] **Step 2:** Write `PathNavigationMixin` `@Inject(method="createPath(...)Lnet/minecraft/world/level/pathfinder/Path;", at=@At("HEAD"), cancellable=true)`. Guard with all config/gate checks; on ineligible return immediately (no cancel → vanilla runs).

- [ ] **Step 3:** Register the sink in `PathWeaver.onInitialize`: `PathWeaverRuntime.get().setSink(new EntityInstallSink())`.

- [ ] **Step 4:** Build + launch dev client/server (`./gradlew runServer`) with a couple of vanilla mobs; confirm no crash, log shows async dispatches. (Manual smoke; the rigorous proof is Task 11.)

Run: `JAVA_HOME="C:/Program Files/Eclipse Adoptium/jdk-25.0.3.9-hotspot" ./gradlew runServer --no-daemon`
Expected: server boots, PathWeaver logs "async dispatch" counters, no exceptions.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dev/pathweaver/mixin/PathNavigationMixin.java src/main/java/dev/pathweaver/async/EntityInstallSink.java src/main/resources/pathweaver.mixins.json
git commit -m "feat: async createPath interceptor + entity install sink (Feature A)"
```

---

## Task 10: Feature B — conservative repath elision

**Files:**
- Modify: `src/main/java/dev/pathweaver/mixin/PathNavigationMixin.java`
- Create: `src/main/java/dev/pathweaver/elision/RepathTolerance.java`
- Test: `src/test/java/dev/pathweaver/elision/RepathToleranceTest.java`

**Interfaces:**
- Produces: `RepathTolerance` pure helper `static boolean canReuseExistingPath(BlockPos oldTarget, BlockPos newTarget, int toleranceBlocks)` = `oldTarget != null && oldTarget.distManhattan(newTarget) <= toleranceBlocks`; and `static boolean changedBlockAffectsPath(Path remaining, BlockPos changed)` = true iff `changed` is within 1 block of any remaining path node. Default tolerance = 1 (config-exposed later if wanted).

- [ ] **Step 1: Failing test** (pure geometry)

```java
package dev.pathweaver.elision;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RepathToleranceTest {
    @Test void reusesWithinTolerance() {
        assertTrue(RepathTolerance.canReuseExistingPath(new BlockPos(0,0,0), new BlockPos(1,0,0), 1));
        assertFalse(RepathTolerance.canReuseExistingPath(new BlockPos(0,0,0), new BlockPos(3,0,0), 1));
    }
    @Test void nullOldTargetForcesRepath() {
        assertFalse(RepathTolerance.canReuseExistingPath(null, new BlockPos(0,0,0), 1));
    }
}
```

- [ ] **Step 2: Run to verify fail**

Run: `./gradlew test --tests '*RepathToleranceTest*'`
Expected: FAIL — class not found.

- [ ] **Step 3: Implement `RepathTolerance`** (the `changedBlockAffectsPath` uses `Path.getNodeCount()`/`getNode(i)`).

```java
package dev.pathweaver.elision;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.pathfinder.Path;

public final class RepathTolerance {
    private RepathTolerance() {}
    public static boolean canReuseExistingPath(BlockPos oldTarget, BlockPos newTarget, int tol) {
        return oldTarget != null && oldTarget.distManhattan(newTarget) <= tol;
    }
    public static boolean changedBlockAffectsPath(Path remaining, BlockPos changed) {
        if (remaining == null) return true;
        for (int i = remaining.getNextNodeIndex(); i < remaining.getNodeCount(); i++) {
            var n = remaining.getNode(i);
            if (Math.abs(n.x - changed.getX()) <= 1
             && Math.abs(n.y - changed.getY()) <= 1
             && Math.abs(n.z - changed.getZ()) <= 1) return true;
        }
        return false;
    }
}
```

- [ ] **Step 4:** In `PathNavigationMixin`, when `config.repathElisionEnabled` and a mob already has a live path to a target within tolerance of the new target, short-circuit `createPath` to return the existing path (cancel). Guard so it never applies when the mob has no current path.

- [ ] **Step 5: Run to verify pass**

Run: `./gradlew test --tests '*RepathToleranceTest*'`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/dev/pathweaver/elision src/test/java/dev/pathweaver/elision src/main/java/dev/pathweaver/mixin/PathNavigationMixin.java
git commit -m "feat: conservative repath elision (Feature B)"
```

---

## Task 11: In-game equivalence + soak (Fabric GameTest) + config GUI

**Files:**
- Create: `src/gametest/java/dev/pathweaver/PathEquivalenceGameTest.java`, `src/gametest/resources/fabric.mod.json` entrypoint
- Create: `src/main/java/dev/pathweaver/config/PathWeaverConfigScreen.java` (cloth-config GUI + AutoConfig serialization)
- Modify: `build.gradle` (gametest source set + `fabric-api` gametest), `fabric.mod.json` (add `fabric-gametest` entrypoint + `modmenu` optional)

**Interfaces:**
- Produces: a gametest that, for a set of fixed start/target/terrain structures, computes a path with async **forced on** and with async **forced off** (via config flags), and asserts the node sequences are identical; plus a soak test spawning N mobs and asserting no exceptions + navigation liveness over M ticks.

- [ ] **Step 1: Write the equivalence gametest**

```java
package dev.pathweaver;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.pathfinder.Path;
import dev.pathweaver.config.PathWeaverConfig;
// ... spawn a mob at A, request path to B twice under both modes, compare node lists.

public class PathEquivalenceGameTest {
    @GameTest(templateNamespace = "pathweaver", template = "flat_5x5")
    public void asyncEqualsSyncOnFlat(GameTestHelper helper) {
        Path sync = computePath(helper, /*async*/ false);
        Path async = computePath(helper, /*async*/ true);  // waits up to K ticks for install
        helper.assertTrue(samePath(sync, async), "async path must equal sync path");
        helper.succeed();
    }
    // computePath toggles PathWeaverConfig.get().asyncEnabled, triggers a nav path, returns result.
    // samePath compares getNodeCount + each node x/y/z.
}
```

- [ ] **Step 2:** Add the gametest source set + `modImplementation` gametest API to `build.gradle`; add a `flat_5x5` structure NBT under `src/gametest/resources/data/pathweaver/structure/`.

- [ ] **Step 3: Run the gametests**

Run: `JAVA_HOME="C:/Program Files/Eclipse Adoptium/jdk-25.0.3.9-hotspot" ./gradlew runGametest --no-daemon`
Expected: `asyncEqualsSyncOnFlat` PASS; soak test PASS (no exceptions, mobs keep valid navigation).

- [ ] **Step 4:** Implement `PathWeaverConfigScreen` with cloth-config `AutoConfig` so the config is editable in-game (ModMenu-visible) and persists to `config/pathweaver.json`. Load into `PathWeaverConfig.set(...)` at init.

- [ ] **Step 5:** Full build.

Run: `JAVA_HOME="C:/Program Files/Eclipse Adoptium/jdk-25.0.3.9-hotspot" ./gradlew build --no-daemon`
Expected: `BUILD SUCCESSFUL`; jar in `build/libs/`.

- [ ] **Step 6: Commit**

```bash
git add src/gametest src/main/java/dev/pathweaver/config/PathWeaverConfigScreen.java build.gradle src/main/resources/fabric.mod.json
git commit -m "test: in-game async==sync equivalence + soak; feat: cloth-config GUI"
```

---

## Task 12: Real-pack validation + publish prep

**Files:**
- Modify: `README.md`, `LICENSE`, `fabric.mod.json` (metadata: sources, issues, contact)

- [ ] **Step 1:** Copy `build/libs/pathweaver-*.jar` into the live pack `mods/`, launch the actual 26.1.2 pack, and confirm: (a) foreign-mixin scan logs stormiespiders/salts_animal_farm denials if present, (b) no crash, (c) spark before/after shows reduced main-thread pathfinding cost in a dense village. Record the MSPT delta.
- [ ] **Step 2:** Write `README.md` leading with the safety argument + equivalence-test proof + explicit "what this does NOT do" (no async ticking/collision) + the Lithium-coordination note + config reference.
- [ ] **Step 3:** Add `LICENSE` (MIT), fill `fabric.mod.json` `contact`/`authors`.
- [ ] **Step 4:** Tag `v0.1.0`, build final jar, prepare Modrinth draft (Fabric 26.1.2, categories: optimization, utility; mark as server-side-capable).
- [ ] **Step 5: Commit**

```bash
git add README.md LICENSE src/main/resources/fabric.mod.json
git commit -m "docs: README with safety proof; publish metadata; v0.1.0"
git tag v0.1.0
```

---

## Self-Review

**Spec coverage:** §2 safety claim → Tasks 3,4,4b,9 (gate + snapshot-only search). §3 Feature A → Tasks 5,6,7,8,9. §3 Feature B → Task 10. §4 all six components → Tasks 2 (config), 3/4 (gate), 5 (snapshot), 6 (pool+request), 7 (installer), 9 (interceptor). §6 correctness (staleness, worker-exception fallback, determinism) → Tasks 6,7,11. §7 mob coverage → verified by Tasks 4/9 gate + Task 12 real-pack. §8 testing → Tasks 3,5,6,7,10 (unit) + 11 (equivalence/soak). §9 build/publish → Tasks 1,11,12. No uncovered spec section.

**Placeholder scan:** The two deliberately-deferred internals (`scanAsmTargets` body → Task 4b; `computePath`/`samePath` gametest bodies → Task 11 steps) are each assigned a concrete task with code, not left as "TODO". Interceptor mixin body (Task 9) is described step-by-step with the exact inject signature + design constraints rather than full code because the exact `@Local` capture set depends on reading the live 26.1.2 `PathNavigation.createPath` body at implementation time — flagged explicitly for the implementer to javap-verify (per the mixin-audit lesson in `porting-26.1.2.md`).

**Type consistency:** `SafetyGate.isAllowed`/`isEvaluatorAllowed`, `SnapshotProvider.rightSizedRadius/getOrBuild/clearTick`, `PathWorkerPool.start/submit/inFlight/shutdown`, `PathRequest(entityId,dispatchTick,search,onDone)`, `ResultInstaller.enqueue/drain`+`InstallSink`, `RepathTolerance.canReuseExistingPath/changedBlockAffectsPath`, `PathWeaverConfig.resolvedPoolThreads` — names consistent across all referencing tasks.

**Known implementation risk (flagged, not a plan gap):** Task 9's async `search` assumes vanilla `PathFinder.findPath` for allowlisted evaluators dereferences no live mob state beyond what's captured at dispatch. The safety audit validated this for the allowlisted classes; Task 11's equivalence test is the enforcing proof. If a captured-context refactor is needed, it lives entirely inside Task 9.
