package dev.sebastiano.indexino.application.selectioncontext

import dev.sebastiano.indexino.core.key.CodeIndexKey
import dev.sebastiano.indexino.core.record.ComposeSelectionSiteRecord
import dev.sebastiano.indexino.core.record.SelectionContainerRef
import dev.sebastiano.indexino.core.xodus.XodusCodeIndexStore
import dev.sebastiano.indexino.producer.IndexBuildContext
import dev.sebastiano.indexino.producer.selectioncontext.SelectionContextProducer
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SelectionContextQueryTest {
    private lateinit var store: XodusCodeIndexStore
    private lateinit var tempDir: java.nio.file.Path
    private lateinit var queryService: SelectionContextQueryService

    @BeforeTest
    fun setUp() {
        tempDir = createTempDirectory("selection-query-")
        store = XodusCodeIndexStore.open(tempDir.resolve("base.xodus"))
        indexFixtures()
        queryService = SelectionContextQueryService(store)
    }

    @AfterTest
    fun tearDown() {
        store.close()
    }

    @Test
    fun `interactive-in-sc preset filters callees in SC without exclusion`() {
        val rows = queryService.queryPreset("interactive-in-sc")
        val files = rows.map { it.file }.toSet()
        assertTrue("nested-sc.kt" in files)
        assertTrue("local-helper.kt" in files)
        assertTrue("import-alias.kt" in files)
        assertFalse("disable-selection.kt" in files)
        assertFalse("no-sc.kt" in files)
        assertEquals(3, rows.size)
    }

    @Test
    fun `nested-selection-container preset filters sc count greater than one`() {
        val rows = queryService.queryPreset("nested-selection-container")
        assertEquals(1, rows.size)
        assertEquals("nested-sc.kt", rows.single().file)
        assertEquals(2, rows.single().selectionContainerCount)
    }

    @Test
    fun `all-call-sites preset returns every indexed site`() {
        val rows = queryService.queryPreset("all-call-sites")
        assertEquals(5, rows.size)
    }

    @Test
    fun `jsonl output has required fields`() {
        queryService =
            SelectionContextQueryService(
                store = store,
                scopeTarget = "//plugins/foo/ui:ui",
                scopeTopology = "bazel-query",
            )
        val rows = queryService.queryPreset("all-call-sites")
        val jsonl = SelectionContextJsonlFormatter.formatLines(rows)
        assertEquals(5, jsonl.size)
        val line = jsonl.first()
        assertTrue(line.contains("\"file\":"))
        assertTrue(line.contains("\"line\":"))
        assertTrue(line.contains("\"column\":"))
        assertTrue(line.contains("\"callee\":"))
        assertTrue(line.contains("\"inSelectionContainer\":"))
        assertTrue(line.contains("\"selectionContainerCount\":"))
        assertTrue(line.contains("\"excludedByDisableSelection\":"))
        assertTrue(line.contains("\"selectionContainers\":"))
        assertTrue(line.contains("\"confidence\":"))
        assertTrue(line.contains("\"target\":\"//plugins/foo/ui:ui\""))
        assertTrue(line.contains("\"topology\":\"bazel-query\""))
    }

    @Test
    fun `point query reads single site by file and line`() {
        val rows = queryService.queryPoint(file = "disable-selection.kt", line = 8)
        assertEquals(1, rows.size)
        val row = rows.single()
        assertEquals("ActionButton", row.callee)
        assertTrue(row.inSelectionContainer)
        assertTrue(row.excludedByDisableSelection)
    }

    @Test
    fun `point query with column disambiguates`() {
        store.put(
            CodeIndexKey.composeSelectionSite("extra.kt", 10, 5),
            ComposeSelectionSiteRecord(
                callee = "IconButton",
                inSelectionContainer = false,
                selectionContainerCount = 0,
                excludedByDisableSelection = false,
                selectionContainers = emptyList(),
            ),
        )
        store.put(
            CodeIndexKey.composeSelectionSite("extra.kt", 10, 20),
            ComposeSelectionSiteRecord(
                callee = "ActionButton",
                inSelectionContainer = true,
                selectionContainerCount = 1,
                excludedByDisableSelection = false,
                selectionContainers = listOf(SelectionContainerRef("extra.kt", 5, "Panel")),
            ),
        )

        val rows = queryService.queryPoint(file = "extra.kt", line = 10, column = 20)
        assertEquals(1, rows.size)
        assertEquals("ActionButton", rows.single().callee)
    }

    private fun indexFixtures() {
        val files =
            listOf(
                    "nested-sc.kt",
                    "disable-selection.kt",
                    "no-sc.kt",
                    "local-helper.kt",
                    "import-alias.kt",
                )
                .associateWith { name ->
                    checkNotNull(
                            javaClass.classLoader.getResourceAsStream(
                                "fixtures/selection-context/$name"
                            )
                        ) {
                            "Missing fixture: $name"
                        }
                        .bufferedReader()
                        .readText()
                }
        SelectionContextProducer()
            .produce(
                IndexBuildContext(
                    store = store,
                    commitHash = "test-commit",
                    sourceFiles = files.keys.toList(),
                    sourceContentOverrides = files,
                )
            )
    }
}
