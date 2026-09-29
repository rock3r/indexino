# Gradle topology: static discovery, not a resolved build model

Gradle is the secondary topology backend. Indexino reads settings/build scripts and walks
conventional source trees; it **never executes the target's Gradle build**. `gradle-parse` identifies
this approximation. Successful discovery does not certify a complete compile/runtime classpath.

## Detection and selection

Auto-detection selects Gradle when the project root has `settings.gradle(.kts)` and no
`MODULE.bazel` / `WORKSPACE`. `--build-system gradle` selects it explicitly. Kotlin settings/build
files take precedence when both DSL files exist.

- `--gradle-module :feature:ui` selects one module, without dependencies by default.
- `--include-deps` adds the transitive syntactically recognized project dependency closure.
- `--gradle-module :` selects the root **and every included module**, regardless of `--include-deps`.
  Root selection therefore reports dependency inclusion in its provenance.

## Recognized literal forms

Single- and double-quoted ordinary strings are supported. Line/block comments (including nested
block comments) and strings containing example declarations are not declarations.

    include(":shell", ":engine:wire-codec")
    include ':shell', ':engine:wire-codec'
    include(project(":shell"))
    project(":engine:wire-codec").projectDir = file("parts/codec")

Literal `projectDir = file(...)` remaps apply to source discovery **and** dependency build-file
lookup, including modules of included builds. Remaps must remain within their build root after
normalization and, for existing directories, canonicalization. External remaps fail rather than
emit parent-relative paths. Other remap expressions are unsupported.

    api(project(":engine:wire-codec"))
    implementation project(':engine:wire-codec')
    api(projects.engine.wireCodec)

The recognized configuration names remain `implementation`, `api`, `compileOnly`, `runtimeOnly`,
and `testImplementation`. Project accessors are mapped against declared module paths; hyphens and
underscores become camel-case components, and colon-separated nesting becomes dotted accessors.
An accessor collision fails rather than picking a module by storage order. Undeclared accessors
are not guessed. Dependency cycles terminate through the existing visited-module set.

**Test source exclusion is not configuration resolution.** `testImplementation` edges are included
for compatibility, while test source sets are excluded. `testRuntimeOnly`, configuration aliases,
custom configurations, `add(...)`, platform wrappers, and dependency substitution are not resolved.
An inventory must label this as a syntactic closure, not a production classpath.

## Source inventory

For each selected module, discovery scans existing files under `src/<sourceSet>/`:

| Directory | Included files |
|---|---|
| `kotlin`, `java` | `.kt`, `.java` |
| `res`, `composeResources` | Files with a non-empty extension, including XML and image resources |

Source-set names containing `test` (case-insensitive) are excluded. Arbitrary source-set names such
as `desktopMain` or `customMain` work **only with this directory convention**. This is not evaluation
of `sourceSets`, Android flavors, or target/variant selection. JVM `resources` directories are not
Android/CMP resource directories.

Missing module/source directories contribute no files under the existing discovery policy. Generated
sources outside `src/<sourceSet>/{kotlin,java,res,composeResources}` and explicit custom `srcDir`
locations are not discovered. Indexino does not generate missing sources. Consequently an empty or
smaller inventory is **incomplete**, not proof that a build has no source there. Acceptance callers
must compare independent expected inventory and report missing/generated inputs separately.

`TopologyResult.codeSourceFiles` is a non-null exact subset of the captured inventory for this
bounded parser: files under supported `kotlin` and `java` source roots. Files under `res` and
`composeResources` remain captured but are not code, regardless of filename extension. Included
build mounts carry the same origin-relative classification in
`ExternalSourceMount.codeSourceFiles`; `null` is reserved for legacy or unknown role evidence.

## Included builds already participate

Literal `includeBuild("tools")`, `includeBuild('../shared')`, and Groovy command forms are supported.
Mounts are canonicalized, recursively deduplicated, and cycle-checked. The allowed external root is
the primary workspace's parent; an unavailable mount or one outside that boundary fails discovery.
External mounts are reported in diagnostics and carried as separate origin-qualified source mounts.

Root selection and `--include-deps` collect each included build's root and included modules. A
target-only request without dependencies reports mounts but does not add their sources. This is a
whole-included-build approximation; substitution rules and per-component dependency edges are not
resolved. It is not a complete Gradle composite build model.

## Unsupported expressions remain incomplete

Interpolated/escaped/triple-quoted declaration arguments, computed paths, variables, loops,
condition evaluation, script plugins, Groovy slashy strings, and arbitrary Gradle model mutation
are outside this bounded parser. Literal declarations inside control flow are syntactically visible;
the parser does not know whether Gradle would execute that branch. Unsupported expressions can
leave the inventory incomplete and are not comprehensively diagnosed today. Do not use a successful
parse alone as a completeness gate or execute arbitrary builds to fill the gap.

## Storage and regression evidence

Storage uses the same **user-local, out-of-worktree** content-addressed cache and generation manifests
as Bazel. See [INDEX-STORAGE.md](INDEX-STORAGE.md). No `.indexino/` product cache is created.

`GradleLiteralTopologyTest` contains newly authored asymmetric Kotlin/Groovy settings, nested
accessors, remapped transitive dependencies, dependency/included-build cycles, root selection,
resource variants, missing/generated/custom paths, and test exclusions. Assertions compare exact
inventories, not successful exits. `GradleIncludedBuildTopologyTest` covers unavailable mounts and
the external-root policy; `ModuleSourceRootsTest` covers module path traversal.
