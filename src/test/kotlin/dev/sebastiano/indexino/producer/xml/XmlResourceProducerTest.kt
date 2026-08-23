package dev.sebastiano.indexino.producer.xml

import dev.sebastiano.indexino.core.record.CodeIndexRecordCodec
import dev.sebastiano.indexino.core.record.ReferenceRecord
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
            "<?xml version=\"1.1\"?>\u0085<resources>\u2028<string name=\"title\">Title</string></resources>"

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
