package dev.sebastiano.indexino.plugin.selection

import dev.sebastiano.indexino.model.PluginFactValue
import dev.sebastiano.indexino.model.SourceFile
import dev.sebastiano.indexino.model.SourceOriginId
import dev.sebastiano.indexino.plugin.api.FileAnalysisContextV1
import dev.sebastiano.indexino.plugin.api.PluginFactSinkV1
import dev.sebastiano.indexino.plugin.selection.parse.KotlinPsiParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.runBlocking

@OptIn(dev.sebastiano.indexino.model.IndexinoInternalApi::class)
class SelectionContextAnalyzerTest {
    @Test
    fun `emits a structured fact for composable inside selection container`() = runBlocking {
        val facts = mutableMapOf<String, PluginFactValue>()
        SelectionContextAnalyzer()
            .analyze(
                FileAnalysisContextV1(
                    file = SourceFile.of(SourceOriginId.of("test"), "Sample.kt", "Sample.kt"),
                    sourceText =
                        """
                        @Composable
                        fun Sample() {
                            SelectionContainer {
                                Button(onClick = {}) {}
                            }
                        }
                        """
                            .trimIndent(),
                    facts =
                        object : PluginFactSinkV1 {
                            override suspend fun put(key: String, value: PluginFactValue) {
                                facts[key] = value
                            }

                            override suspend fun putAt(
                                key: String,
                                range: dev.sebastiano.indexino.model.SourceRange?,
                                value: PluginFactValue,
                            ) = put(key, value)
                        },
                    active = { true },
                )
            )
        val fact = assertNotNull(facts.entries.firstOrNull { it.key.startsWith("selection-site:") })
        val fields = (fact.value as PluginFactValue.Struct).fields
        assertEquals(PluginFactValue.Bool.of(true), fields["inSelectionContainer"])
        assertEquals(PluginFactValue.Text.of("Button"), fields["callee"])
    }

    @Test
    fun `emits a structured fact for a CRLF composable source with original-file positions`() =
        runBlocking {
            // core.autocrlf=true checkouts carry CRLF bytes; write them explicitly so the fixture
            // is identical on every platform.
            val source =
                """
                @Composable
                fun Sample() {
                    SelectionContainer {
                        Button(onClick = {}) {}
                    }
                }
                """
                    .trimIndent()
                    .replace("\n", "\r\n")
            val facts = mutableMapOf<String, PluginFactValue>()
            val ranges = mutableMapOf<String, dev.sebastiano.indexino.model.SourceRange?>()
            SelectionContextAnalyzer()
                .analyze(
                    FileAnalysisContextV1(
                        file = SourceFile.of(SourceOriginId.of("test"), "Crlf.kt", "Crlf.kt"),
                        sourceText = source,
                        facts =
                            object : PluginFactSinkV1 {
                                override suspend fun put(key: String, value: PluginFactValue) {
                                    facts[key] = value
                                }

                                override suspend fun putAt(
                                    key: String,
                                    range: dev.sebastiano.indexino.model.SourceRange?,
                                    value: PluginFactValue,
                                ) {
                                    facts[key] = value
                                    ranges[key] = range
                                }
                            },
                        active = { true },
                    )
                )
            val fact =
                assertNotNull(facts.entries.firstOrNull { it.key.startsWith("selection-site:") })
            val fields = (fact.value as PluginFactValue.Struct).fields
            assertEquals(PluginFactValue.Text.of("Button"), fields["callee"])
            val range = assertNotNull(ranges[fact.key])
            val callStart = source.indexOf("Button(")
            assertEquals(4, range.start.line)
            assertEquals(
                callStart - source.lastIndexOf('\n', callStart - 1) - 1 + 1,
                range.start.column,
            )
            assertEquals(callStart, range.start.offset)
        }

    @Test
    fun `uses host supplied syntax without creating a parser`() = runBlocking {
        val source =
            """
            @Composable
            fun Sample() {
                SelectionContainer {
                    Button(onClick = {}) {}
                }
            }
            """
                .trimIndent()
        val shared = KotlinPsiParser().use { parser -> parser.parseFile("Shared.kt", source) }
        val facts = mutableMapOf<String, PluginFactValue>()
        val sink =
            object : PluginFactSinkV1, Function1<String, Any?> {
                override suspend fun put(key: String, value: PluginFactValue) {
                    facts[key] = value
                }

                override suspend fun putAt(
                    key: String,
                    range: dev.sebastiano.indexino.model.SourceRange?,
                    value: PluginFactValue,
                ) = put(key, value)

                override fun invoke(p1: String): Any? = if (p1 == source) shared else null
            }
        SelectionContextAnalyzer(parserFactory = { error("shared syntax must be used") })
            .analyze(
                FileAnalysisContextV1(
                    file = SourceFile.of(SourceOriginId.of("test"), "Shared.kt", "Shared.kt"),
                    sourceText = source,
                    facts = sink,
                    active = { true },
                )
            )
        val fact = assertNotNull(facts.entries.firstOrNull { it.key.startsWith("selection-site:") })
        assertEquals(
            PluginFactValue.Text.of("Button"),
            (fact.value as PluginFactValue.Struct).fields["callee"],
        )
    }
}
