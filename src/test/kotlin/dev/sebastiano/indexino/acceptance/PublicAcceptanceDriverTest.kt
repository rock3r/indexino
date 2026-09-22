// SPDX-License-Identifier: UEL-1.0
@file:OptIn(dev.sebastiano.indexino.model.IndexinoInternalApi::class)

package dev.sebastiano.indexino.acceptance

import dev.sebastiano.indexino.model.QueryPage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.runBlocking

class PublicAcceptanceDriverTest {
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
}
