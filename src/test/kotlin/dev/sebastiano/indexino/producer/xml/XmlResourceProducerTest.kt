package dev.sebastiano.indexino.producer.xml

import dev.sebastiano.indexino.core.record.CodeIndexRecordCodec
import dev.sebastiano.indexino.core.record.ReferenceRecord
import dev.sebastiano.indexino.core.record.ResourceDefinitionRecord
import dev.sebastiano.indexino.core.record.ResourceUsageRecord
import dev.sebastiano.indexino.core.record.SymbolRecord
import dev.sebastiano.indexino.core.xodus.XodusCodeIndexStore
import dev.sebastiano.indexino.producer.IndexBuildContext
import dev.sebastiano.indexino.producer.IndexedSource
import dev.sebastiano.indexino.producer.ProducerRegistry
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class XmlResourceProducerTest {
    @Test
    fun `preserves one based declaration columns for XML symbols`() {
        val values =
            """
            <resources>
                <!-- <fake> -->
                <![CDATA[<also-fake>]]>
                <string
                    name="title">Title</string>
            </resources>
            """
                .trimIndent()
        val layout =
            """
            <LinearLayout
                xmlns:android="http://schemas.android.com/apk/res/android"
                xmlns:tools="http://schemas.android.com/tools">
                <TextView
                    note=" android:id='decoy' "
                    android:id="@+id/title"
                    tools:id="
                        @+id/preview_title" />
            </LinearLayout>
            """
                .trimIndent()

        withStore { store ->
            checkNotNull(ProducerRegistry.get("xml-resources"))
                .produce(
                    IndexBuildContext.forInlineSources(
                        store = store,
                        commitHash = "columns",
                        sourceFiles =
                            mapOf(
                                "src/main/res/values/strings.xml" to values,
                                "src/main/res/layout/main.xml" to layout,
                            ),
                    )
                )

            val encodedSymbols =
                store
                    .prefixScan("res:")
                    .map { CodeIndexRecordCodec.encode(it.second).decodeToString() }
                    .toList()
            assertTrue(encodedSymbols.any { "\"name\":\"main\"" in it && "\"column\":1" in it })
            assertTrue(
                encodedSymbols.any {
                    "\"name\":\"title\"" in it && "\"line\":4" in it && "\"column\":5" in it
                }
            )
            assertTrue(
                encodedSymbols.any {
                    "\"name\":\"title\"" in it && "\"line\":6" in it && "\"column\":21" in it
                }
            )
            assertTrue(
                encodedSymbols.any {
                    "\"name\":\"preview_title\"" in it &&
                        "\"line\":8" in it &&
                        "\"column\":13" in it
                }
            )
        }
    }

    @Test
    fun `preserves raw XML reference positions across text and entities`() {
        val values =
            """
            <resources>
                <string name="plain">@string/title</string>
                <string name="cdata"><![CDATA[@string/title]]></string>
                <string name="comment"><!-- @string/title -->@string/title</string>
                <string name="entity-text">@string/foo&#95;bar</string>
                <string name="literal"><![CDATA[@string/foo&#95;bar]]></string>
            </resources>
            """
                .trimIndent()
        val layout =
            """
            <TextView
                note="&#10;@string/title"
                android:text="@string/foo&#95;bar"
                xmlns:android="http://schemas.android.com/apk/res/android" />
            """
                .trimIndent()
        val crlfLayout =
            "<TextView xmlns:android=\"http://schemas.android.com/apk/res/android\" android:text=\"\r\n @string/title\" />"
        val crLayout =
            "<TextView xmlns:android=\"http://schemas.android.com/apk/res/android\"\r android:text=\"@string/title\" />"

        withStore { store ->
            assertNotNull(ProducerRegistry.get("xml-resources"))
                .produce(
                    IndexBuildContext.forInlineSources(
                        store = store,
                        commitHash = "raw-reference-columns",
                        sourceFiles =
                            mapOf(
                                "src/main/res/values/strings.xml" to values,
                                "src/main/res/layout/main.xml" to layout,
                                "src/main/res/layout/crlf.xml" to crlfLayout,
                                "src/main/res/layout/cr.xml" to crLayout,
                            ),
                    )
                )

            val references =
                store
                    .prefixScan("ref:res:string:")
                    .map { it.second }
                    .filterIsInstance<ReferenceRecord>()
                    .toList()
            assertReference(references, "res:string:title", "strings.xml", 2, 26)
            assertReference(references, "res:string:title", "strings.xml", 3, 35)
            assertReference(references, "res:string:title", "strings.xml", 4, 50)
            assertReference(references, "res:string:foo_bar", "strings.xml", 5, 32)
            assertReference(references, "res:string:foo", "strings.xml", 6, 37)
            assertReference(references, "res:string:title", "main.xml", 2, 16)
            assertReference(references, "res:string:foo_bar", "main.xml", 3, 19)
            assertReference(references, "res:string:title", "crlf.xml", 2, 2)
            assertReference(references, "res:string:title", "cr.xml", 2, 16)
        }
    }

    @Test
    fun `keeps same line duplicate XML declarations distinct`() {
        val layout =
            """<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"><View android:id="@+id/item"/><View android:id="@+id/item"/></LinearLayout>"""

        withStore { store ->
            assertNotNull(ProducerRegistry.get("xml-resources"))
                .produce(
                    IndexBuildContext.forInlineSources(
                        store = store,
                        commitHash = "same-line-columns",
                        sourceFiles = mapOf("src/main/res/layout/main.xml" to layout),
                    )
                )

            val declarations =
                store
                    .prefixScan("res:id:item")
                    .map { it.second }
                    .filterIsInstance<SymbolRecord>()
                    .toList()
            assertEquals(listOf(92, 122), declarations.map { it.column }.sorted())
        }
    }

    @Test
    fun `skips complete internal DTD subsets when locating declarations`() {
        val values =
            """
            <!DOCTYPE resources [<!-- [ --><?pi [?><!ENTITY unused '<fake><bar>'>]>
            <resources>
                <string name="title">Title</string>
            </resources>
            """
                .trimIndent()

        withStore { store ->
            assertNotNull(ProducerRegistry.get("xml-resources"))
                .produce(
                    IndexBuildContext.forInlineSources(
                        store = store,
                        commitHash = "internal-dtd-columns",
                        sourceFiles = mapOf("src/main/res/values/strings.xml" to values),
                    )
                )

            val title =
                store
                    .prefixScan("res:string:title")
                    .map { it.second }
                    .filterIsInstance<SymbolRecord>()
                    .single()
            assertEquals(3, title.line)
            assertEquals(5, title.column)
        }
    }

    @Test
    fun `counts XML 1_1 line separators in declaration locations`() {
        val values =
            "<?xml version=\"1.1\"?>\r\u0085<resources>\u2028<string\u0085name=\"title\">Title</string></resources>"

        withStore { store ->
            assertNotNull(ProducerRegistry.get("xml-resources"))
                .produce(
                    IndexBuildContext.forInlineSources(
                        store = store,
                        commitHash = "xml-1-1-columns",
                        sourceFiles = mapOf("src/main/res/values/strings.xml" to values),
                    )
                )

            val title =
                store
                    .prefixScan("res:string:title")
                    .map { it.second }
                    .filterIsInstance<SymbolRecord>()
                    .single()
            assertEquals(3, title.line)
            assertEquals(1, title.column)
        }
    }

    @Test
    fun `keeps XML 1_1-only separators on the same line in XML 1_0`() {
        val values = "<resources>\u0085<string name=\"title\">Title</string>\u2028</resources>"

        withStore { store ->
            assertNotNull(ProducerRegistry.get("xml-resources"))
                .produce(
                    IndexBuildContext.forInlineSources(
                        store = store,
                        commitHash = "xml-1-0-columns",
                        sourceFiles = mapOf("src/main/res/values/strings.xml" to values),
                    )
                )

            val title =
                store
                    .prefixScan("res:string:title")
                    .map { it.second }
                    .filterIsInstance<SymbolRecord>()
                    .single()
            assertEquals(1, title.line)
            assertEquals(13, title.column)
        }
    }

    @Test
    fun `keeps equal resource paths from separate origins distinct`() {
        val firstRoot = createTempDirectory("indexino-resource-origin-first-")
        val secondRoot = createTempDirectory("indexino-resource-origin-second-")
        val relativePath = "src/main/res/values/strings.xml"
        firstRoot
            .resolve(relativePath)
            .also { it.parent.createDirectories() }
            .writeText("<resources><string name=\"title\">First</string></resources>")
        secondRoot
            .resolve(relativePath)
            .also { it.parent.createDirectories() }
            .writeText("<resources><string name=\"title\">Second</string></resources>")

        withStore { store ->
            val producer = assertNotNull(ProducerRegistry.get("xml-resources"))
            producer.produce(
                IndexBuildContext(
                    store = store,
                    commitHash = "abc",
                    sourceFiles = listOf(relativePath),
                    sources =
                        listOf(
                            IndexedSource("git:first", firstRoot, relativePath),
                            IndexedSource("git:second", secondRoot, relativePath),
                        ),
                )
            )

            val resources =
                store.prefixScan("res:").map { it.second }.filterIsInstance<SymbolRecord>().toList()
            assertEquals(
                setOf("git:first", "git:second"),
                resources.filter { it.fqn == "res:string:title" }.map { it.originId }.toSet(),
            )
            assertTrue(resources.all { it.relativeFile == relativePath })
        }
    }

    @Test
    fun `indexes file values and id resources with references`() {
        val values =
            """
            <resources>
                <string name="title">Hello</string>
                <item type="color" name="accent">#ff0000</item>
                <item type="color" name="accent_alias">@color/accent</item>
                <declare-styleable name="CustomView"><attr name="accent" /></declare-styleable>
                <string-array name="items"><item>One</item></string-array>
            </resources>
            """
                .trimIndent()
        val layout =
            """
            <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android">
                <TextView
                    android:id="@+id/title_view"
                    android:text="@string/title"
                    android:textColor="@android:color/white"
                    android:entries="@array/items" />
            </LinearLayout>
            """
                .trimIndent()

        withStore { store ->
            val producer = assertNotNull(ProducerRegistry.get("xml-resources"))
            producer.produce(
                IndexBuildContext.forInlineSources(
                    store = store,
                    commitHash = "abc",
                    sourceFiles =
                        mapOf(
                            "app/src/main/res/values/strings.xml" to values,
                            "app/src/main/res/layout/main_screen.xml" to layout,
                        ),
                )
            )

            val resources =
                store.prefixScan("res:").map { it.second }.filterIsInstance<SymbolRecord>().toList()
            assertTrue(resources.any { it.fqn == "res:string:title" })
            assertTrue(resources.any { it.fqn == "res:color:accent" })
            assertTrue(resources.any { it.fqn == "res:layout:main_screen" })
            assertTrue(resources.any { it.fqn == "res:id:title_view" })
            assertTrue(resources.any { it.fqn == "res:styleable:CustomView" })
            assertTrue(resources.any { it.fqn == "res:attr:accent" })
            assertTrue(resources.any { it.fqn == "res:styleable:CustomView_accent" })
            assertTrue(resources.any { it.fqn == "res:array:items" })

            val references =
                store
                    .prefixScan("ref:")
                    .map { it.second }
                    .filterIsInstance<ReferenceRecord>()
                    .toList()
            assertTrue(
                references.any { it.symbolFqn == "res:string:title" && it.context == "resource" }
            )
            assertTrue(
                references.any { it.symbolFqn == "res:color:accent" && it.context == "resource" }
            )
            assertTrue(
                references.any { it.symbolFqn == "res:array:items" && it.context == "resource" }
            )
            assertTrue(references.any { it.symbolFqn == "res:android:color:white" })
            assertTrue(references.none { it.symbolFqn == "res:color:white" })

            val idDefinition =
                store
                    .prefixScan("resdef:")
                    .map { it.second }
                    .filterIsInstance<ResourceDefinitionRecord>()
                    .single { it.type == "id" && it.name == "title_view" }
            assertEquals(layout.indexOf("@+id/title_view"), idDefinition.offset)
            assertEquals(3, idDefinition.line)
            assertEquals(21, idDefinition.column)

            val usages =
                store
                    .prefixScan("resuse:")
                    .map { it.second }
                    .filterIsInstance<ResourceUsageRecord>()
                    .toList()
            assertTrue(
                usages.any {
                    it.packageName == null &&
                        it.type == "string" &&
                        it.name == "title" &&
                        it.language == "xml" &&
                        it.offset == layout.indexOf("@string/title")
                }
            )
            assertTrue(
                usages.any {
                    it.packageName == "android" && it.type == "color" && it.name == "white"
                }
            )
        }
    }

    @Test
    fun `preserves resource packages and qualifiers from project metadata`() {
        val sources =
            mapOf(
                "app/src/main/AndroidManifest.xml" to
                    "<manifest package=\"com.example.manifest\" />",
                "app/build.gradle.kts" to "android { namespace = \"com.example.app\" }",
                "app/src/main/res/values/strings.xml" to
                    "<resources><string name=\"title\">Hello</string></resources>",
                "app/src/main/res/values-night/colors.xml" to
                    "<resources><color name=\"accent\">#fff</color></resources>",
                "feature/build.gradle" to "android { namespace 'com.example.feature' }",
                "feature/src/main/res/values/strings.xml" to
                    "<resources><string name=\"title\">Feature</string></resources>",
            )

        withStore { store ->
            val producer = assertNotNull(ProducerRegistry.get("xml-resources"))
            producer.produce(
                IndexBuildContext.forInlineSources(
                    store = store,
                    commitHash = "resources",
                    sourceFiles = sources,
                )
            )

            val definitions =
                store
                    .prefixScan("resdef:")
                    .map { it.second }
                    .filterIsInstance<ResourceDefinitionRecord>()
                    .toList()
            assertEquals(
                setOf(
                    "com.example.app:string:title:",
                    "com.example.app:color:accent:night",
                    "com.example.feature:string:title:",
                ),
                definitions
                    .map {
                        listOf(it.packageName.orEmpty(), it.type, it.name, it.qualifiers)
                            .joinToString(":")
                    }
                    .toSet(),
            )
        }
    }

    @Test
    fun `reads package metadata from origin root when metadata is not indexed`() {
        val root = createTempDirectory("indexino-resource-metadata-root-")
        root
            .resolve("app/build.gradle.kts")
            .also { it.parent.createDirectories() }
            .writeText("android { namespace = \"com.example.disk\" }")
        root
            .resolve("app/src/main/res/values/strings.xml")
            .also { it.parent.createDirectories() }
            .writeText("<resources><string name=\"title\">Hello</string></resources>")

        withStore { store ->
            val producer = assertNotNull(ProducerRegistry.get("xml-resources"))
            producer.produce(
                IndexBuildContext(
                    store = store,
                    commitHash = "resources",
                    sourceFiles = listOf("app/src/main/res/values/strings.xml"),
                    sources =
                        listOf(
                            IndexedSource("workspace", root, "app/src/main/res/values/strings.xml")
                        ),
                )
            )

            val definition =
                store
                    .prefixScan("resdef:")
                    .map { it.second }
                    .filterIsInstance<ResourceDefinitionRecord>()
                    .single()
            assertEquals("com.example.disk", definition.packageName)
            assertTrue(definition.offset > 0)
        }
    }

    @Test
    fun `indexes non XML file resources as definitions without parsing content`() {
        val root = createTempDirectory("indexino-file-resource-root-")
        root
            .resolve("app/build.gradle.kts")
            .also { it.parent.createDirectories() }
            .writeText("android { namespace = \"com.example.assets\" }")
        root
            .resolve("app/src/main/res/drawable/icon.png")
            .also { it.parent.createDirectories() }
            .writeText("not-a-real-png")
        root
            .resolve("app/src/main/res/drawable/button.9.png")
            .also { it.parent.createDirectories() }
            .writeText("not-a-real-nine-patch")

        withStore { store ->
            val producer = assertNotNull(ProducerRegistry.get("xml-resources"))
            producer.produce(
                IndexBuildContext(
                    store = store,
                    commitHash = "resources",
                    sourceFiles =
                        listOf(
                            "app/src/main/res/drawable/icon.png",
                            "app/src/main/res/drawable/button.9.png",
                        ),
                    sources =
                        listOf(
                            IndexedSource("workspace", root, "app/src/main/res/drawable/icon.png"),
                            IndexedSource(
                                "workspace",
                                root,
                                "app/src/main/res/drawable/button.9.png",
                            ),
                        ),
                )
            )

            val definitions =
                store
                    .prefixScan("resdef:")
                    .map { it.second }
                    .filterIsInstance<ResourceDefinitionRecord>()
                    .toList()
            assertEquals(setOf("icon", "button"), definitions.map { it.name }.toSet())
            assertTrue(definitions.all { it.packageName == "com.example.assets" })
            assertTrue(definitions.all { it.type == "drawable" })
        }
    }

    @Test
    fun `manifest values that mention namespace do not outrank manifest package`() {
        val sources =
            mapOf(
                "app/src/main/AndroidManifest.xml" to
                    "<manifest package=\"com.example.manifest\">" +
                        "<provider authorities=\"com.example.namespace\" />" +
                        "</manifest>",
                "app/src/main/res/values/strings.xml" to
                    "<resources><string name=\"title\">Hello</string></resources>",
            )

        withStore { store ->
            val producer = assertNotNull(ProducerRegistry.get("xml-resources"))
            producer.produce(
                IndexBuildContext.forInlineSources(
                    store = store,
                    commitHash = "resources",
                    sourceFiles = sources,
                )
            )

            val definition =
                store
                    .prefixScan("resdef:")
                    .map { it.second }
                    .filterIsInstance<ResourceDefinitionRecord>()
                    .single()
            assertEquals("com.example.manifest", definition.packageName)
        }
    }

    @Test
    fun `manifest package outranks application id when namespace is absent`() {
        val sources =
            mapOf(
                "app/src/main/AndroidManifest.xml" to
                    "<manifest package=\"com.example.manifest\" />",
                "app/build.gradle.kts" to "android { applicationId = \"com.example.application\" }",
                "app/src/main/res/values/strings.xml" to
                    "<resources><string name=\"title\">Hello</string></resources>",
            )

        withStore { store ->
            val producer = assertNotNull(ProducerRegistry.get("xml-resources"))
            producer.produce(
                IndexBuildContext.forInlineSources(
                    store = store,
                    commitHash = "resources",
                    sourceFiles = sources,
                )
            )

            val definition =
                store
                    .prefixScan("resdef:")
                    .map { it.second }
                    .filterIsInstance<ResourceDefinitionRecord>()
                    .single()
            assertEquals("com.example.manifest", definition.packageName)
        }
    }

    @Test
    fun `resolves Bazel resource metadata from the owning package root`() {
        val root = createTempDirectory("indexino-bazel-resource-root-")
        root
            .resolve("app/build.gradle.kts")
            .also { it.parent.createDirectories() }
            .writeText("android { namespace = \"com.example.bazel\" }")
        root
            .resolve("app/feature_res/drawable/feature_icon.xml")
            .also { it.parent.createDirectories() }
            .writeText("<shape />")

        withStore { store ->
            val producer = assertNotNull(ProducerRegistry.get("xml-resources"))
            producer.produce(
                IndexBuildContext(
                    store = store,
                    commitHash = "resources",
                    sourceFiles = listOf("app/feature_res/drawable/feature_icon.xml"),
                    sources =
                        listOf(
                            IndexedSource(
                                "workspace",
                                root,
                                "app/feature_res/drawable/feature_icon.xml",
                            )
                        ),
                )
            )

            val definition =
                store
                    .prefixScan("resdef:")
                    .map { it.second }
                    .filterIsInstance<ResourceDefinitionRecord>()
                    .single()
            assertEquals("com.example.bazel", definition.packageName)
        }
    }

    @Test
    fun `indexes CMP resources with source set provenance and package metadata`() {
        val root = createTempDirectory("indexino-cmp-resource-root-")
        root
            .resolve("app/build.gradle.kts")
            .also { it.parent.createDirectories() }
            .writeText("android { namespace = \"com.example.cmp\" }")
        root
            .resolve("app/src/commonMain/composeResources/values-night/strings.xml")
            .also { it.parent.createDirectories() }
            .writeText("<resources><string name=\"title\">Hello</string></resources>")

        withStore { store ->
            val producer = assertNotNull(ProducerRegistry.get("xml-resources"))
            producer.produce(
                IndexBuildContext(
                    store = store,
                    commitHash = "resources",
                    sourceFiles =
                        listOf("app/src/commonMain/composeResources/values-night/strings.xml"),
                    sources =
                        listOf(
                            IndexedSource(
                                "workspace",
                                root,
                                "app/src/commonMain/composeResources/values-night/strings.xml",
                            )
                        ),
                )
            )

            val definition =
                store
                    .prefixScan("resdef:")
                    .map { it.second }
                    .filterIsInstance<ResourceDefinitionRecord>()
                    .single()
            assertEquals("com.example.cmp", definition.packageName)
            assertEquals("night", definition.qualifiers)
            assertTrue(definition.column > 1)
        }
    }

    @Test
    fun `resource package metadata changes refresh dependent definitions`() {
        val initial =
            mapOf(
                "app/src/main/AndroidManifest.xml" to "<manifest package=\"com.example.old\" />",
                "app/src/main/res/values/strings.xml" to
                    "<resources><string name=\"title\">Hello</string></resources>",
                "feature/build.gradle" to "android { namespace 'com.example.oldfeature' }",
                "feature/src/main/res/values/strings.xml" to
                    "<resources><string name=\"title\">Feature</string></resources>",
            )

        withStore { store ->
            val producer = assertNotNull(ProducerRegistry.get("xml-resources"))
            producer.produce(
                IndexBuildContext.forInlineSources(
                    store = store,
                    commitHash = "initial",
                    sourceFiles = initial,
                )
            )

            val updated =
                initial +
                    mapOf(
                        "app/src/main/AndroidManifest.xml" to
                            "<manifest package=\"com.example.new\" />",
                        "feature/build.gradle" to "android { namespace 'com.example.newfeature' }",
                    )
            producer.produce(
                IndexBuildContext(
                    store = store,
                    commitHash = "updated",
                    sourceFiles = updated.keys.toList(),
                    sourceContentOverrides = updated,
                    changedSourceFiles =
                        setOf("app/src/main/AndroidManifest.xml", "feature/build.gradle"),
                )
            )

            val packages =
                store
                    .prefixScan("resdef:")
                    .map { it.second }
                    .filterIsInstance<ResourceDefinitionRecord>()
                    .map { it.packageName }
                    .toSet()
            assertEquals(setOf("com.example.new", "com.example.newfeature"), packages)
        }
    }

    @Test
    fun `resource package metadata deletion clears dependent package identity`() {
        val resources =
            mapOf(
                "app/build.gradle.kts" to "android { namespace = \"com.example.transient\" }",
                "app/src/main/res/values/strings.xml" to
                    "<resources><string name=\"title\">Hello</string></resources>",
            )

        withStore { store ->
            val producer = assertNotNull(ProducerRegistry.get("xml-resources"))
            producer.produce(
                IndexBuildContext.forInlineSources(
                    store = store,
                    commitHash = "initial",
                    sourceFiles = resources,
                )
            )
            val remaining =
                mapOf(
                    "app/src/main/res/values/strings.xml" to
                        resources.getValue("app/src/main/res/values/strings.xml")
                )
            producer.produce(
                IndexBuildContext(
                    store = store,
                    commitHash = "deleted",
                    sourceFiles = remaining.keys.toList(),
                    sourceContentOverrides = remaining,
                    deletedSourceFiles = setOf("app/build.gradle.kts"),
                )
            )

            val packages =
                store
                    .prefixScan("resdef:")
                    .map { it.second }
                    .filterIsInstance<ResourceDefinitionRecord>()
                    .map { it.packageName }
                    .toSet()
            assertEquals(setOf(null), packages)
        }
    }

    @Test
    fun `rejects external entity expansion`() {
        val malicious =
            """
            <!DOCTYPE resources [<!ENTITY secret SYSTEM "file:///etc/passwd">]>
            <resources><string name="title">&secret;</string></resources>
            """
                .trimIndent()

        withStore { store ->
            val producer = assertNotNull(ProducerRegistry.get("xml-resources"))
            val failure =
                runCatching {
                        producer.produce(
                            IndexBuildContext.forInlineSources(
                                store = store,
                                commitHash = "abc",
                                sourceFiles =
                                    mapOf("app/src/main/res/values/strings.xml" to malicious),
                            )
                        )
                    }
                    .exceptionOrNull()
            assertNotNull(failure)
            assertTrue(failure.message.orEmpty().contains("strings.xml"))
        }
    }

    @Test
    fun `indexes file resources from Bazel style res paths`() {
        withStore { store ->
            val producer = assertNotNull(ProducerRegistry.get("xml-resources"))
            producer.produce(
                IndexBuildContext.forInlineSources(
                    store = store,
                    commitHash = "abc",
                    sourceFiles =
                        mapOf(
                            "app/res/layout/bazel_screen.xml" to "<FrameLayout />",
                            "app/feature_res/drawable/feature_icon.xml" to "<shape />",
                            "app/src/main/java/com/example/response/layout/form.xml" to "<form />",
                        ),
                )
            )

            val resources =
                store.prefixScan("res:").map { it.second }.filterIsInstance<SymbolRecord>().toList()
            assertTrue(resources.any { it.fqn == "res:layout:bazel_screen" })
            assertTrue(resources.any { it.fqn == "res:drawable:feature_icon" })
            assertFalse(resources.any { it.fqn == "res:layout:form" })
        }
    }

    private fun withStore(block: (XodusCodeIndexStore) -> Unit) {
        val store =
            XodusCodeIndexStore.open(createTempDirectory("xml-resource-producer-").resolve("index"))
        try {
            block(store)
        } finally {
            store.close()
        }
    }

    private fun assertReference(
        references: List<ReferenceRecord>,
        symbolFqn: String,
        fileSuffix: String,
        line: Int,
        column: Int,
    ) {
        assertTrue(
            references.any {
                it.symbolFqn == symbolFqn &&
                    it.relativeFile.endsWith(fileSuffix) &&
                    it.line == line &&
                    it.column == column
            }
        )
    }
}
