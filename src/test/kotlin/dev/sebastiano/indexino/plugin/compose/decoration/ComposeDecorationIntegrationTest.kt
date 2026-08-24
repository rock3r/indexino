package dev.sebastiano.indexino.plugin.compose.decoration

import dev.sebastiano.indexino.core.plugin.StorePluginFactView
import dev.sebastiano.indexino.core.xodus.XodusCodeIndexStore
import dev.sebastiano.indexino.engine.PluginAnalyzerRunner
import dev.sebastiano.indexino.engine.PluginRegistry
import dev.sebastiano.indexino.model.IndexinoInternalApi
import dev.sebastiano.indexino.model.QueryOptions
import dev.sebastiano.indexino.producer.IndexBuildContext
import dev.sebastiano.indexino.producer.ProducerRegistry
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

@OptIn(IndexinoInternalApi::class)
class ComposeDecorationIntegrationTest {
    @Test
    fun `indexes modifier chains from call facts without reparsing source`() {
        val root = createTempDirectory("compose-decoration-integration-")
        val store = XodusCodeIndexStore.open(root.resolve("store"))
        val fixture =
            javaClass
                .getResource("/fixtures/compose-decoration/direct-and-helper-chains.kt")!!
                .readText()
        val context =
            IndexBuildContext.forInlineSources(
                store = store,
                commitHash = "compose-decoration",
                sourceFiles = mapOf("Sample.kt" to fixture),
            )
        try {
            checkNotNull(ProducerRegistry.get("kotlin-psi-symbols")).produce(context, store)
            val registry = PluginRegistry.load(javaClass.classLoader)
            PluginAnalyzerRunner(registry)
                .analyze(context, setOf("dev.sebastiano.compose-decoration"))
            val facts =
                StorePluginFactView(store, "dev.sebastiano.compose-decoration", "__postprocess__")
            val sites = runBlocking {
                ComposeDecorationQueries.findSites(facts, QueryOptions.page(100))
            }
            assertTrue(sites.items.isNotEmpty(), "expected decoration sites")
            val helperSite = sites.items.single { it.composableCalleeName == "Box" }
            assertEquals(1, helperSite.chain.links.size)
            assertEquals(ModifierLinkKind.HELPER, helperSite.chain.links.single().kind)
            assertEquals("screenModifier", helperSite.chain.links.single().calleeName)
            val noModifier = sites.items.single { it.composableCalleeName == "Column" }
            assertEquals(false, noModifier.hasModifierArgument)
        } finally {
            store.close()
            root.toFile().deleteRecursively()
        }
    }
}
