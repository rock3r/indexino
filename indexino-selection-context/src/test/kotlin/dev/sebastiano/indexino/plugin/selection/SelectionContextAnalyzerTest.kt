package dev.sebastiano.indexino.plugin.selection

import dev.sebastiano.indexino.model.PluginFactValue
import dev.sebastiano.indexino.model.SourceFile
import dev.sebastiano.indexino.model.SourceOriginId
import dev.sebastiano.indexino.plugin.api.FileAnalysisContextV1
import dev.sebastiano.indexino.plugin.api.PluginFactSinkV1
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
}
