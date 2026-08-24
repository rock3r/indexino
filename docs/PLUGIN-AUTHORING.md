# Plugin authoring

Compiled plugins depend on `indexino-plugin-api` and declare their minimum required plugin ABI in
the JAR manifest:

```text
Indexino-Plugin-ABI-Target: 1.0.0
```

The target is the ABI metadata embedded by the exact `indexino-plugin-api` dependency used to
compile the plugin. It is not the plugin version, the Indexino product version, the plugin fact
schema, or a handwritten compatibility range. A plugin build may target an older ABI only after
the plugin compatibility test kit proves that the compiled artifact links and runs against it.

Indexino's reference `indexino-selection-context` plugin demonstrates the build contract: its JAR
task reads the generated metadata from `indexino-plugin-api` and writes the target attribute. A
third-party build should likewise resolve `META-INF/indexino/plugin-abi.properties` from its
compile dependency and copy the `current` value into the manifest.

## Load compatibility

The JVM host reads every external plugin manifest before creating its plugin classloader or using
`ServiceLoader`. A plugin loads only when its target lies in the generated host interval. For the
current single-major model this means:

```text
host ABI >= plugin target ABI
host ABI major == plugin target ABI major
```

An incompatible or missing target fails before provider class loading. The diagnostic includes the
host ABI, plugin target ABI, supported range, and whether to rebuild the plugin or upgrade the host.
Bundled closed-world/native plugins are built in the same release train; arbitrary external JARs
remain a JVM-only capability.

## Separate version coordinates

- Plugin ABI: compatibility of `indexino-plugin-api` classes and required behavior.
- Plugin version: the plugin artifact's own release version.
- Basic fact schema: host facts required by the plugin descriptor.
- Plugin fact schema: persisted values owned by that plugin namespace.

Changing one does not implicitly change the others. Additive plugin API changes increment the ABI
minor. Breaking binary, source, or required behavioral changes increment the ABI major. Compatible
fixes may increment the patch without changing the Metalava surface.
