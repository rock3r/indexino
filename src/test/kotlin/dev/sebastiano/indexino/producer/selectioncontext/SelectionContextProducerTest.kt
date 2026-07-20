package dev.sebastiano.indexino.producer.selectioncontext

import dev.sebastiano.indexino.core.key.CodeIndexKey
import dev.sebastiano.indexino.core.record.ComposeSelectionSiteRecord
import dev.sebastiano.indexino.core.xodus.XodusCodeIndexStore
import dev.sebastiano.indexino.producer.IndexBuildContext
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class SelectionContextProducerTest {
    private lateinit var store: XodusCodeIndexStore
    private lateinit var tempDir: java.nio.file.Path
    private val commitHash = "abc123test"

    @BeforeTest
    fun setUp() {
        tempDir = createTempDirectory("selection-producer-")
        store = XodusCodeIndexStore.open(tempDir.resolve("base.xodus"))
    }

    @AfterTest
    fun tearDown() {
        store.close()
    }

    @Test
    fun `indexes all fixture call sites with golden keys and records`() {
        val overrides = fixtureSourceFiles()
        val context =
            IndexBuildContext(
                store = store,
                commitHash = commitHash,
                sourceFiles = overrides.keys.toList(),
                sourceContentOverrides = overrides,
            )
        SelectionContextProducer().produce(context)

        val allKeys = store.prefixScan("compose:selection-site:").toList()
        assertEquals(5, allKeys.size)

        assertSite(
            relativeFile = "nested-sc.kt",
            line = 8,
            expectedCallee = "ActionButton",
            inSc = true,
            scCount = 2,
            excluded = false,
        )
        assertSite(
            relativeFile = "disable-selection.kt",
            line = 8,
            expectedCallee = "ActionButton",
            inSc = true,
            scCount = 1,
            excluded = true,
        )
        assertSite(
            relativeFile = "no-sc.kt",
            line = 6,
            expectedCallee = "ActionButton",
            inSc = false,
            scCount = 0,
            excluded = false,
        )
        assertSite(
            relativeFile = "local-helper.kt",
            line = 8,
            expectedCallee = "ActionButton",
            inSc = true,
            scCount = 1,
            excluded = false,
        )
        assertSite(
            relativeFile = "import-alias.kt",
            line = 9,
            expectedCallee = "ActionButton",
            inSc = true,
            scCount = 1,
            excluded = false,
        )
    }

    @Test
    fun `re-index deletes stale keys for file prefix before re-emit`() {
        val staleKey = CodeIndexKey.composeSelectionSite("nested-sc.kt", 99, 1)
        store.put(
            staleKey,
            ComposeSelectionSiteRecord(
                callee = "Stale",
                inSelectionContainer = false,
                selectionContainerCount = 0,
                excludedByDisableSelection = false,
                selectionContainers = emptyList(),
            ),
        )

        val context =
            IndexBuildContext(
                store = store,
                commitHash = commitHash,
                sourceFiles = listOf("nested-sc.kt"),
                sourceContentOverrides = mapOf("nested-sc.kt" to fixture("nested-sc.kt")),
            )
        SelectionContextProducer().produce(context)

        assertEquals(null, store.get(staleKey))
        assertEquals(1, store.prefixScan("compose:selection-site:nested-sc.kt:").count())
    }

    @Test
    fun `re-index clears selection sites outside current source set`() {
        val otherFileKey = CodeIndexKey.composeSelectionSite("no-sc.kt", 6, 1)
        store.put(
            otherFileKey,
            ComposeSelectionSiteRecord(
                callee = "ActionButton",
                inSelectionContainer = false,
                selectionContainerCount = 0,
                excludedByDisableSelection = false,
                selectionContainers = emptyList(),
            ),
        )

        val context =
            IndexBuildContext(
                store = store,
                commitHash = commitHash,
                sourceFiles = listOf("nested-sc.kt"),
                sourceContentOverrides = mapOf("nested-sc.kt" to fixture("nested-sc.kt")),
            )
        SelectionContextProducer().produce(context)

        assertEquals(null, store.get(otherFileKey))
        assertEquals(1, store.prefixScan("compose:selection-site:").count())
    }

    private fun assertSite(
        relativeFile: String,
        line: Int,
        expectedCallee: String,
        inSc: Boolean,
        scCount: Int,
        excluded: Boolean,
    ) {
        val prefix = "compose:selection-site:$relativeFile:$line:"
        val matches = store.prefixScan(prefix).toList()
        assertEquals(1, matches.size, "Expected one site at $relativeFile:$line")
        val record = matches.single().second as ComposeSelectionSiteRecord
        assertEquals(expectedCallee, record.callee)
        assertEquals(inSc, record.inSelectionContainer)
        assertEquals(scCount, record.selectionContainerCount)
        assertEquals(excluded, record.excludedByDisableSelection)
        assertEquals(commitHash, record.indexedFromCommit)
        assertEquals("lexical", record.confidence)
    }

    private fun fixtureSourceFiles(): Map<String, String> =
        listOf(
                "nested-sc.kt",
                "disable-selection.kt",
                "no-sc.kt",
                "local-helper.kt",
                "import-alias.kt",
            )
            .associateWith { fixture(it) }

    private fun fixture(name: String): String =
        checkNotNull(
                javaClass.classLoader.getResourceAsStream("fixtures/selection-context/$name")
            ) {
                "Missing fixture: $name"
            }
            .bufferedReader()
            .readText()
}
