// SPDX-License-Identifier: UEL-1.0
@file:OptIn(dev.sebastiano.indexino.model.IndexinoInternalApi::class)

package dev.sebastiano.indexino.acceptance

import dev.sebastiano.indexino.model.QueryPage
import dev.sebastiano.indexino.producer.IndexedSource
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir

class PublicAcceptanceDriverTest {
    @Test
    fun `captured inventory keeps nested origin prefixes and rejects outside origins`(
        @TempDir root: Path
    ) {
        val workspace = Files.createDirectory(root.resolve("workspace")).toRealPath()
        val android = Files.createDirectory(workspace.resolve("android"))
        Files.writeString(workspace.resolve("Same.kt"), "class Main")
        Files.writeString(android.resolve("Same.kt"), "class Android")
        val nested =
            listOf(
                IndexedSource("main", workspace, "Same.kt"),
                IndexedSource("android", android, "Same.kt"),
            )
        assertEquals(
            listOf("Same.kt", "android/Same.kt"),
            capturedInventoryPaths(workspace, nested),
        )
        val outside = Files.createDirectory(root.resolve("workspace-outside"))
        Files.writeString(outside.resolve("Same.kt"), "class Outside")
        assertFailsWith<IllegalStateException> {
            capturedInventoryPaths(workspace, listOf(IndexedSource("outside", outside, "Same.kt")))
        }
    }

    @Test
    fun `call mutations reject stale references and stale callers independently`() {
        assertCallMutationRows(
            listOf("Moved.kt:8", "Second.kt:3"),
            listOf("Second.kt:3", "Moved.kt:8"),
            listOf("Moved.kt:8", "Second.kt:3"),
        )
        assertFailsWith<IllegalStateException> {
            assertCallMutationRows(
                listOf("Moved.kt:8"),
                listOf("Original.kt:3"),
                listOf("Moved.kt:8"),
            )
        }
        assertFailsWith<IllegalStateException> {
            assertCallMutationRows(emptyList(), emptyList(), listOf("Deleted.kt:3"))
        }
        assertFailsWith<IllegalStateException> {
            assertCallMutationRows(
                listOf("Moved.kt:8"),
                listOf("Moved.kt:8", "Moved.kt:8"),
                listOf("Moved.kt:8"),
            )
        }
    }

    @Test
    fun `pagination rejects a server returning the wrong offset`(): Unit = runBlocking {
        assertFailsWith<IllegalStateException> {
            collectPages { QueryPage(listOf("a"), 9, 2, false, null, 1) }
        }
    }

    @Test
    fun `pagination requires every asymmetric expected row exactly once`(): Unit = runBlocking {
        val records = listOf("first", "second", "third", "fourth", "last")
        val actual = collectPages { options ->
            QueryPage(
                records.drop(options.offset).take(options.limit),
                options.offset,
                options.limit,
                options.offset + options.limit < records.size,
                null,
                records.size,
            )
        }
        assertEquals(records, actual)
        assertFailsWith<IllegalStateException> {
            collectPages { options ->
                QueryPage(listOf("same"), options.offset, options.limit, true, null, 5)
            }
        }
        assertFailsWith<IllegalStateException> {
            collectPages { QueryPage(listOf("first"), 0, 2, false, null, 5) }
        }
    }

    @Test
    fun `multi item pages retain all records and reject duplicates across boundaries`(): Unit =
        runBlocking {
            val records = listOf("a", "b", "c", "d", "e", "f", "g")
            val offsets = mutableListOf<Int>()
            val actual =
                collectPages(pageSize = 3) { options ->
                    offsets += options.offset
                    QueryPage(
                        records.drop(options.offset).take(options.limit),
                        options.offset,
                        options.limit,
                        options.offset + options.limit < records.size,
                        null,
                        records.size,
                    )
                }
            assertEquals(records, actual)
            assertEquals(listOf(0, 3, 6), offsets)
            assertFailsWith<IllegalStateException> {
                collectPages(pageSize = 3) { options ->
                    QueryPage(listOf("duplicate"), options.offset, options.limit, true, null, null)
                }
            }
        }
}
