package dev.sebastiano.indexino.plugin.compose.decoration

import dev.sebastiano.indexino.core.plugin.StorePluginFactView
import dev.sebastiano.indexino.core.xodus.XodusCodeIndexStore
import dev.sebastiano.indexino.engine.PluginAnalyzerRunner
import dev.sebastiano.indexino.engine.PluginRegistry
import dev.sebastiano.indexino.model.IndexinoInternalApi
import dev.sebastiano.indexino.model.QueryOptions
import dev.sebastiano.indexino.producer.IndexBuildContext
import dev.sebastiano.indexino.producer.ProducerRegistry
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

@OptIn(IndexinoInternalApi::class)
class ComposeDecorationIntegrationTest {
    @Test
    fun `indexes modifier chains from call facts without reparsing source`() {
        val sites = analyzeFixture("/fixtures/compose-decoration/direct-and-helper-chains.kt")
        assertTrue(sites.isNotEmpty(), "expected decoration sites")
        val helperSite = sites.single { it.composableCalleeName == "Box" }
        assertEquals(1, helperSite.chain.links.size)
        assertEquals(ModifierLinkKind.HELPER, helperSite.chain.links.single().kind)
        assertEquals("screenModifier", helperSite.chain.links.single().calleeName)
        val noModifier = sites.single { it.composableCalleeName == "Column" }
        assertEquals(false, noModifier.hasModifierArgument)
    }

    @Test
    fun `fixture matrix covers decoration scenarios from stored facts`() {
        val sites = analyzeFixture("/fixtures/compose-decoration/modifier-matrix.kt")
        assertDirectAndOrderedModifierChains(sites)
        assertThenAndHelperModifierChains(sites)
        assertConditionalAndComposedModifierChains(sites)
        assertAliasNestedAndForwardedModifierChains(sites)
        assertUnresolvedAndMissingModifierChains(sites)
    }

    @Test
    fun `incremental refresh replaces only affected decoration facts`() {
        val root = createTempDirectory("compose-decoration-incremental-")
        val store = XodusCodeIndexStore.open(root.resolve("store"))
        val initialFixture =
            """
            @Target(AnnotationTarget.FUNCTION)
            annotation class Composable

            object Modifier { fun padding(all: Int): Modifier = this }

            @Composable fun First(modifier: Modifier = Modifier) {}
            @Composable fun Second(modifier: Modifier = Modifier) {}
            @Composable fun FirstSample() { First(modifier = Modifier.padding(8)) }
            @Composable fun SecondSample() { Second() }
            """
                .trimIndent()
        val updatedFixture =
            initialFixture.replace("Second()", "Second(modifier = Modifier.padding(4))")
        try {
            analyzeInline(store, "First.kt", initialFixture)
            val initialSites = querySites(store)
            assertEquals(1, initialSites.count { it.hasModifierArgument })

            analyzeInline(store, "First.kt", updatedFixture)
            val refreshedSites = querySites(store)
            assertEquals(2, refreshedSites.count { it.hasModifierArgument })
        } finally {
            store.close()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `loads compose decoration from external plugin jar`() {
        val pluginJar = Path.of(requiredProperty("indexino.composeDecorationJar"))
        assertTrue(pluginJar.toFile().isFile, "compose-decoration jar missing: $pluginJar")

        val parent =
            object : ClassLoader(javaClass.classLoader) {
                override fun getResources(name: String): java.util.Enumeration<java.net.URL> =
                    if (name == PLUGIN_PROVIDER_SERVICE) {
                        java.util.Collections.emptyEnumeration()
                    } else {
                        super.getResources(name)
                    }
            }
        val registry = PluginRegistry.load(pluginJars = listOf(pluginJar), parent = parent)
        assertEquals(
            "Compose decoration",
            registry
                .descriptor(
                    dev.sebastiano.indexino.model.PluginId.of("dev.sebastiano.compose-decoration")
                )
                ?.displayName,
        )
        assertTrue(
            registry.postProcessors.any {
                it.pluginId.value == "dev.sebastiano.compose-decoration" &&
                    it.processor.id == "modifier-chains"
            }
        )
    }

    private fun assertDirectAndOrderedModifierChains(sites: List<DecorationSite>) {
        assertTrue(
            sites.any {
                it.composableCalleeName == "Text" &&
                    it.chain.links
                        .map { link -> link.calleeName }
                        .containsAll(listOf("padding", "fillMaxWidth"))
            },
            "direct modifier chain",
        )
        assertTrue(
            sites.any {
                it.composableCalleeName == "Text" &&
                    it.hasModifierArgument &&
                    it.chain.links.singleOrNull()?.calleeName == "padding"
            },
            "ordered modifier argument",
        )
    }

    private fun assertThenAndHelperModifierChains(sites: List<DecorationSite>) {
        val thenChain = sites.single { it.composableCalleeName == "Row" }
        assertTrue(thenChain.chain.links.any { it.kind == ModifierLinkKind.THEN }, "then chain")
        assertTrue(
            sites.any { site ->
                site.composableCalleeName == "Box" &&
                    site.chain.links.any { link ->
                        link.kind == ModifierLinkKind.HELPER && link.calleeName == "screenModifier"
                    }
            },
            "helper-returned modifier",
        )
    }

    private fun assertConditionalAndComposedModifierChains(sites: List<DecorationSite>) {
        assertTrue(
            sites.any { site ->
                site.composableCalleeName == "Column" &&
                    site.chain.links.any { link ->
                        link.kind == ModifierLinkKind.CONDITIONAL ||
                            (link.kind == ModifierLinkKind.DIRECT &&
                                link.calleeName == "fillMaxWidth")
                    }
            },
            "conditional modifier",
        )
        assertTrue(
            sites.any {
                it.composableCalleeName == "Box" &&
                    it.chain.links.any { link -> link.kind == ModifierLinkKind.COMPOSED }
            },
            "composed modifier",
        )
    }

    private fun assertAliasNestedAndForwardedModifierChains(sites: List<DecorationSite>) {
        val aliasChains = sites.filter {
            it.composableCalleeName == "Text" &&
                it.chain.links.map { link -> link.calleeName } == listOf("padding", "fillMaxWidth")
        }
        assertTrue(aliasChains.size >= 2, "import alias chain")
        assertTrue(
            sites.any { site ->
                site.composableCalleeName == "Box" &&
                    site.chain.links.any { link ->
                        link.calleeName == "padding" || link.calleeName == "outerModifier"
                    }
            },
            "nested modifier call",
        )
        assertTrue(
            sites.any { site ->
                site.composableCalleeName == "Box" &&
                    site.chain.links.any { link ->
                        link.kind == ModifierLinkKind.HELPER &&
                            link.calleeName == "forwardedPadding"
                    }
            },
            "forwarded modifier parameter",
        )
    }

    private fun assertUnresolvedAndMissingModifierChains(sites: List<DecorationSite>) {
        assertTrue(
            sites.any { site ->
                site.composableCalleeName == "Box" &&
                    site.chain.links.any { link -> link.calleeName == "maybeModifier" }
            },
            "ambiguous or helper modifier callee",
        )
        assertTrue(
            sites.any { it.composableCalleeName == "Column" && !it.hasModifierArgument },
            "composable without modifier",
        )
    }

    private fun analyzeFixture(resourcePath: String): List<DecorationSite> {
        val root = createTempDirectory("compose-decoration-integration-")
        val store = XodusCodeIndexStore.open(root.resolve("store"))
        val fixture = javaClass.getResource(resourcePath)!!.readText()
        return try {
            analyzeInline(store, "Sample.kt", fixture)
            querySites(store)
        } finally {
            store.close()
            root.toFile().deleteRecursively()
        }
    }

    private fun analyzeInline(store: XodusCodeIndexStore, fileName: String, source: String) {
        val context =
            IndexBuildContext.forInlineSources(
                store = store,
                commitHash = "compose-decoration",
                sourceFiles = mapOf(fileName to source),
            )
        checkNotNull(ProducerRegistry.get("kotlin-psi-symbols")).produce(context, store)
        val registry = PluginRegistry.load(javaClass.classLoader)
        PluginAnalyzerRunner(registry).analyze(context, setOf("dev.sebastiano.compose-decoration"))
    }

    private fun querySites(store: XodusCodeIndexStore): List<DecorationSite> {
        val facts =
            StorePluginFactView(store, "dev.sebastiano.compose-decoration", "__postprocess__")
        return runBlocking {
            ComposeDecorationQueries.findSites(facts, QueryOptions.page(100)).items
        }
    }

    private fun requiredProperty(name: String): String =
        checkNotNull(System.getProperty(name)) { "Missing system property $name" }

    private companion object {
        private const val PLUGIN_PROVIDER_SERVICE =
            "META-INF/services/dev.sebastiano.indexino.plugin.api.IndexinoPluginProvider"
    }
}
