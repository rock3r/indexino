package dev.sebastiano.indexino.api

import dev.sebastiano.indexino.core.key.CodeIndexKey
import dev.sebastiano.indexino.core.record.ResourceDefinitionRecord
import dev.sebastiano.indexino.core.record.ResourceUsageRecord
import dev.sebastiano.indexino.core.xodus.XodusCodeIndexStore
import dev.sebastiano.indexino.model.IndexinoInternalApi
import dev.sebastiano.indexino.model.QueryOptions
import dev.sebastiano.indexino.model.ResourceId
import dev.sebastiano.indexino.model.ResourceQuery
import dev.sebastiano.indexino.model.SourceOriginId
import dev.sebastiano.indexino.model.SourceOriginRevision
import dev.sebastiano.indexino.model.WorkspaceGenerationId
import dev.sebastiano.indexino.model.WorkspaceRevision
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.coroutines.runBlocking

@OptIn(IndexinoInternalApi::class)
class IndexSnapshotResourceTest {
    @Test
    fun `resource definition and usage queries are deterministic and identity aware`() {
        val store =
            XodusCodeIndexStore.open(createTempDirectory("resource-snapshot-").resolve("index"))
        try {
            seedDefinitions(store)
            seedUsages(store)
            val snapshot = snapshot(store)
            try {
                val definitions = runBlocking {
                    snapshot.findResources(
                        ResourceQuery.named(ResourceId.of("com.example.app", "string", "title")),
                        QueryOptions.page(10),
                    )
                }
                assertEquals(listOf("", "night"), definitions.items.map { it.qualifiers })
                assertEquals(listOf(10, 20), definitions.items.map { it.location.offset })
                assertFalse(definitions.hasMore)

                val usages = runBlocking {
                    snapshot.findResourceUsages(
                        ResourceQuery.named(
                            ResourceId.of("com.example.feature", "string", "title")
                        ),
                        QueryOptions.page(10),
                    )
                }
                assertEquals(listOf("java"), usages.items.map { it.language })
                assertEquals(listOf(93), usages.items.map { it.location.offset })
                assertEquals(
                    listOf("app/src/main/java/Screen.java"),
                    usages.items.map { it.location.file.path },
                )
            } finally {
                snapshot.close()
            }
        } finally {
            store.close()
        }
    }

    private fun seedDefinitions(store: XodusCodeIndexStore) {
        listOf(
                ResourceDefinitionRecord(
                    packageName = "com.example.app",
                    type = "string",
                    name = "title",
                    qualifiers = "",
                    relativeFile = "app/src/main/res/values/strings.xml",
                    line = 2,
                    offset = 10,
                ),
                ResourceDefinitionRecord(
                    packageName = "com.example.app",
                    type = "string",
                    name = "title",
                    qualifiers = "night",
                    relativeFile = "app/src/main/res/values-night/strings.xml",
                    line = 2,
                    offset = 20,
                ),
                ResourceDefinitionRecord(
                    packageName = "com.example.feature",
                    type = "string",
                    name = "title",
                    qualifiers = "",
                    relativeFile = "feature/src/main/res/values/strings.xml",
                    line = 2,
                    offset = 30,
                ),
            )
            .forEach { record ->
                store.put(
                    CodeIndexKey.resourceDefinition(
                        record.packageName,
                        record.type,
                        record.name,
                        record.qualifiers,
                        record.originId,
                        record.relativeFile,
                        record.line,
                    ),
                    record,
                )
            }
    }

    private fun seedUsages(store: XodusCodeIndexStore) {
        listOf(
                ResourceUsageRecord(
                    packageName = "com.example.app",
                    type = "string",
                    name = "title",
                    relativeFile = "app/src/main/kotlin/Screen.kt",
                    line = 8,
                    column = 24,
                    offset = 72,
                    language = "kotlin",
                ),
                ResourceUsageRecord(
                    packageName = "com.example.feature",
                    type = "string",
                    name = "title",
                    relativeFile = "app/src/main/java/Screen.java",
                    line = 9,
                    column = 31,
                    offset = 93,
                    language = "java",
                ),
            )
            .forEach { record ->
                store.put(
                    CodeIndexKey.resourceUsage(
                        record.packageName,
                        record.type,
                        record.name,
                        record.originId,
                        record.relativeFile,
                        record.line,
                        record.column,
                    ),
                    record,
                )
            }
    }

    private fun snapshot(store: XodusCodeIndexStore): IndexSnapshot =
        IndexSnapshot.create(
            store = store,
            revision =
                WorkspaceRevision(
                    "fingerprint",
                    listOf(
                        SourceOriginRevision(
                            SourceOriginId.of("workspace"),
                            "revision",
                            "state",
                            expectedRevision = null,
                        )
                    ),
                ),
            generation = WorkspaceGenerationId.of("generation"),
        )
}
