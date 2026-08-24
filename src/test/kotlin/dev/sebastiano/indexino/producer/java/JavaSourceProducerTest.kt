package dev.sebastiano.indexino.producer.java

import dev.sebastiano.indexino.core.record.CallSiteRecord
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class JavaSourceProducerTest {
    @Test
    fun `preserves one based declaration columns for Java symbols`() {
        val source =
            """
            class FirstColumn {
                void multiline(
                    String value
                ) {}
            }
            """
                .trimIndent()

        withStore { store ->
            checkNotNull(ProducerRegistry.get("java-source"))
                .produce(
                    IndexBuildContext.forInlineSources(
                        store = store,
                        commitHash = "columns",
                        sourceFiles = mapOf("Columns.java" to source),
                    )
                )

            val encodedSymbols =
                store
                    .prefixScan("sym:")
                    .map { CodeIndexRecordCodec.encode(it.second).decodeToString() }
                    .toList()
            assertTrue(
                encodedSymbols.any { "\"name\":\"FirstColumn\"" in it && "\"column\":1" in it }
            )
            assertTrue(
                encodedSymbols.any { "\"name\":\"multiline\"" in it && "\"column\":5" in it }
            )
        }
    }

    @Test
    fun `keeps equal Java paths from separate origins distinct`() {
        val firstRoot = createTempDirectory("indexino-java-origin-first-")
        val secondRoot = createTempDirectory("indexino-java-origin-second-")
        val relativePath = "src/main/java/sample/Panel.java"
        firstRoot
            .resolve(relativePath)
            .also { it.parent.createDirectories() }
            .writeText("package sample; class Panel { void first() {} }")
        secondRoot
            .resolve(relativePath)
            .also { it.parent.createDirectories() }
            .writeText("package sample; class Panel { void second() {} }")

        withStore { store ->
            checkNotNull(ProducerRegistry.get("java-source"))
                .produce(
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

            val symbols =
                store.prefixScan("sym:").map { it.second }.filterIsInstance<SymbolRecord>()
            assertEquals(
                setOf("git:first", "git:second"),
                symbols.filter { it.fqn == "sample.Panel" }.map { it.originId }.toSet(),
            )
        }
    }

    @Test
    fun `indexes Java declarations and reconstructable references`() {
        val source =
            """
            package sample;

            import java.util.List;

            public class Panel {
                private String title;

                public void render(List<String> items) {
                    helper(items.size());
                    this.helper(items.size());
                }

                private void helper(int count) {}
            }
            """
                .trimIndent()

        withStore { store ->
            val producer = assertNotNull(ProducerRegistry.get("java-source"))
            producer.produce(
                IndexBuildContext.forInlineSources(
                    store = store,
                    commitHash = "abc",
                    sourceFiles = mapOf("src/main/java/sample/Panel.java" to source),
                )
            )

            val symbols =
                store.prefixScan("sym:").map { it.second }.filterIsInstance<SymbolRecord>().toList()
            assertTrue(symbols.any { it.fqn == "sample.Panel" && it.kind == "class" })
            assertTrue(symbols.any { it.fqn == "sample.Panel#title" && it.kind == "field" })
            assertTrue(symbols.any { it.fqn == "sample.Panel#render" && it.kind == "method" })
            assertTrue(symbols.any { it.fqn == "sample.Panel#helper" && it.kind == "method" })

            val references =
                store
                    .prefixScan("ref:")
                    .map { it.second }
                    .filterIsInstance<ReferenceRecord>()
                    .toList()
            assertTrue(
                references.any { it.symbolFqn == "java.util.List" && it.context == "import" }
            )
            assertTrue(
                references.any { it.symbolFqn == "sample.Panel#helper" && it.context == "call" }
            )
            assertEquals(2, references.count { it.symbolFqn == "sample.Panel#helper" })
        }
    }

    @Test
    fun `indexes Java method calls with argument ranges`() {
        val source =
            """
            package sample;
            class Calls {
                void child() {}
                void outer(Runnable content) { content.run(); }
                void render() { outer(() -> child()); }
            }
            """
                .trimIndent()
        withStore { store ->
            checkNotNull(ProducerRegistry.get("java-source"))
                .produce(
                    IndexBuildContext.forInlineSources(
                        store = store,
                        commitHash = "calls",
                        sourceFiles = mapOf("Calls.java" to source),
                    )
                )
            val calls =
                store
                    .prefixScan("call:")
                    .map { it.second }
                    .filterIsInstance<CallSiteRecord>()
                    .toList()
            val outer = calls.first { it.calleeName == "outer" }
            assertEquals(1, outer.arguments.size)
            assertEquals("LAMBDA", outer.arguments.single().kind)
            assertEquals("content", outer.arguments.single().resolvedName)
            assertEquals("sample.Calls#render", outer.enclosingSymbolFqn)
            val child = calls.first { it.calleeName == "child" }
            assertEquals(listOf(child.identity), outer.arguments.single().nestedCallIdentities)
            assertTrue(outer.startOffset < outer.endOffset)
            assertEquals(')', source[outer.endOffset])
        }
    }

    @Test
    fun `indexes Java constructor calls and nested constructor arguments`() {
        val source =
            """
            package sample;
            class Child {}
            class Parent { Parent(Child child) {} }
            class Calls { void render() { new Parent(new Child()); } }
            """
                .trimIndent()
        withStore { store ->
            checkNotNull(ProducerRegistry.get("java-source"))
                .produce(
                    IndexBuildContext.forInlineSources(
                        store = store,
                        commitHash = "constructors",
                        sourceFiles = mapOf("Calls.java" to source),
                    )
                )
            val calls =
                store
                    .prefixScan("call:")
                    .map { it.second }
                    .filterIsInstance<CallSiteRecord>()
                    .toList()
            val parent = calls.first { it.calleeName == "Parent" }
            val child = calls.first { it.calleeName == "Child" }
            assertEquals(listOf(child.identity), parent.arguments.single().nestedCallIdentities)
            assertEquals("sample.Calls#render", parent.enclosingSymbolFqn)
        }
    }

    @Test
    fun `preserves overloaded declarations as distinct persisted records`() {
        val source =
            """
            package sample;

            class Formatter {
                void format(String value) {}
                void format(int value) {}
            }
            """
                .trimIndent()

        withStore { store ->
            val producer = assertNotNull(ProducerRegistry.get("java-source"))
            producer.produce(
                IndexBuildContext.forInlineSources(
                    store = store,
                    commitHash = "abc",
                    sourceFiles = mapOf("Formatter.java" to source),
                )
            )

            val overloads =
                store
                    .prefixScan("sym:")
                    .map { it.second }
                    .filterIsInstance<SymbolRecord>()
                    .filter { it.fqn == "sample.Formatter#format" }
                    .toList()
            assertEquals(2, overloads.size)
        }
    }

    @Test
    fun `resolves unqualified calls through static imports`() {
        val source =
            """
            package sample;
            import static sample.Util.render;
            class Caller { void call() { render(); } }
            """
                .trimIndent()

        withStore { store ->
            val producer = assertNotNull(ProducerRegistry.get("java-source"))
            producer.produce(
                IndexBuildContext.forInlineSources(
                    store = store,
                    commitHash = "abc",
                    sourceFiles = mapOf("Caller.java" to source),
                )
            )

            val references =
                store
                    .prefixScan("ref:")
                    .map { it.second }
                    .filterIsInstance<ReferenceRecord>()
                    .toList()
            assertEquals(2, references.count { it.symbolFqn == "sample.Util#render" })
            assertTrue(references.none { it.symbolFqn == "sample.Util.render" })
            assertTrue(references.none { it.symbolFqn == "sample.Caller#render" })
        }
    }

    @Test
    fun `prefers local methods over static wildcard imports`() {
        val source =
            """
            package sample;
            import static sample.Util.*;
            class Caller {
                void render() {}
                void call() { render(); }
            }
            """
                .trimIndent()

        withStore { store ->
            val producer = assertNotNull(ProducerRegistry.get("java-source"))
            producer.produce(
                IndexBuildContext.forInlineSources(
                    store = store,
                    commitHash = "abc",
                    sourceFiles = mapOf("Caller.java" to source),
                )
            )

            val references =
                store
                    .prefixScan("ref:")
                    .map { it.second }
                    .filterIsInstance<ReferenceRecord>()
                    .toList()
            assertTrue(references.any { it.symbolFqn == "sample.Caller#render" })
            assertTrue(references.none { it.symbolFqn == "sample.Util#render" })
        }
    }

    @Test
    fun `prefers local methods over explicit static imports`() {
        val source =
            """
            package sample;
            import static sample.Util.render;
            class Caller {
                void render() {}
                void call() { render(); }
            }
            """
                .trimIndent()

        withStore { store ->
            val producer = assertNotNull(ProducerRegistry.get("java-source"))
            producer.produce(
                IndexBuildContext.forInlineSources(
                    store = store,
                    commitHash = "abc",
                    sourceFiles = mapOf("Caller.java" to source),
                )
            )

            val calls =
                store
                    .prefixScan("ref:")
                    .map { it.second }
                    .filterIsInstance<ReferenceRecord>()
                    .filter { it.context == "call" }
                    .toList()
            assertTrue(calls.any { it.symbolFqn == "sample.Caller#render" })
            assertTrue(calls.none { it.symbolFqn == "sample.Util#render" })
        }
    }

    @Test
    fun `Java local receiver types expire at block boundaries`() {
        val source =
            """
            package sample;
            class First { void render() {} }
            class Second { void render() {} }
            class Caller {
                void call(Iterable<Second> items) {
                    { Second model = null; model.render(); this.model.render(); }
                    for (Second model : items) { model.render(); }
                    model.render();
                }
                First model;
            }
            """
                .trimIndent()

        withStore { store ->
            val producer = assertNotNull(ProducerRegistry.get("java-source"))
            producer.produce(
                IndexBuildContext.forInlineSources(
                    store = store,
                    commitHash = "abc",
                    sourceFiles = mapOf("Caller.java" to source),
                )
            )

            val references =
                store
                    .prefixScan("ref:")
                    .map { it.second }
                    .filterIsInstance<ReferenceRecord>()
                    .toList()
            assertEquals(2, references.count { it.symbolFqn == "sample.Second#render" })
            assertEquals(2, references.count { it.symbolFqn == "sample.First#render" })
            assertTrue(references.none { it.symbolFqn == "this.model#render" })
        }
    }

    @Test
    fun `Java catch resource and super receivers retain their declared owners`() {
        val source =
            """
            package sample;
            class First implements AutoCloseable {
                void render() {}
                public void close() {}
            }
            class Second extends RuntimeException implements AutoCloseable {
                void render() {}
                public void close() {}
            }
            class Base { void render() {} }
            class Caller extends Base {
                First model;
                First resource;
                void call() {
                    try (Second model = new Second()) { model.render(); }
                    try { throw new Second(); } catch (Second model) { model.render(); }
                    try (Second resource = new Second()) { resource.render(); }
                    catch (RuntimeException ignored) { resource.render(); }
                    finally { resource.render(); }
                    model.render();
                    super.render();
                }
            }
            """
                .trimIndent()

        withStore { store ->
            val producer = assertNotNull(ProducerRegistry.get("java-source"))
            producer.produce(
                IndexBuildContext.forInlineSources(
                    store = store,
                    commitHash = "abc",
                    sourceFiles = mapOf("Caller.java" to source),
                )
            )

            val references =
                store
                    .prefixScan("ref:")
                    .map { it.second }
                    .filterIsInstance<ReferenceRecord>()
                    .toList()
            assertEquals(3, references.count { it.symbolFqn == "sample.Second#render" })
            assertEquals(3, references.count { it.symbolFqn == "sample.First#render" })
            assertEquals(1, references.count { it.symbolFqn == "sample.Base#render" })
            assertTrue(references.none { it.symbolFqn == "sample.Caller#render" })
        }
    }

    @Test
    fun `Java inherited fallback verifies members and infers var initializers`() {
        val source =
            """
            package sample;
            class Renderer { void render() {} }
            class Base { void inherited() {} }
            class Child extends Base {
                void call() {
                    inherited();
                    unrelated();
                    var renderer = new Renderer();
                    renderer.render();
                }
            }
            """
                .trimIndent()

        withStore { store ->
            val producer = assertNotNull(ProducerRegistry.get("java-source"))
            producer.produce(
                IndexBuildContext.forInlineSources(
                    store = store,
                    commitHash = "abc",
                    sourceFiles = mapOf("Child.java" to source),
                )
            )

            val references =
                store
                    .prefixScan("ref:")
                    .map { it.second }
                    .filterIsInstance<ReferenceRecord>()
                    .toList()
            assertTrue(references.any { it.symbolFqn == "sample.Base#inherited" })
            assertTrue(references.none { it.symbolFqn == "sample.Base#unrelated" })
            assertTrue(
                references.any { it.symbolFqn == "sample.Renderer#render" },
                references.joinToString("\n"),
            )
            assertTrue(references.none { it.symbolFqn == "sample.var#render" })
        }
    }

    @Test
    fun `anonymous Java members use an anonymous owner`() {
        val source =
            """
            package sample;
            class Outer {
                void helper() {}
                void call() {
                    Runnable task = new Runnable() {
                        public void run() { helper(); }
                    };
                }
            }
            """
                .trimIndent()

        withStore { store ->
            val producer = assertNotNull(ProducerRegistry.get("java-source"))
            producer.produce(
                IndexBuildContext.forInlineSources(
                    store = store,
                    commitHash = "abc",
                    sourceFiles = mapOf("Outer.java" to source),
                )
            )

            val symbols =
                store.prefixScan("sym:").map { it.second }.filterIsInstance<SymbolRecord>().toList()
            assertTrue(symbols.none { it.fqn == "sample.Outer#run" })
            assertTrue(
                symbols.any {
                    it.name == "run" && it.ownerFqn?.startsWith("sample.Outer.<anonymous@") == true
                }
            )
            val references =
                store
                    .prefixScan("ref:")
                    .map { it.second }
                    .filterIsInstance<ReferenceRecord>()
                    .toList()
            assertTrue(references.any { it.symbolFqn == "sample.Outer#helper" })
        }
    }

    @Test
    fun `implicit Java lambda parameters do not abort indexing`() {
        val source =
            """
            package sample;
            import java.util.List;
            class Item { void render() {} }
            class Caller {
                void call(List<Item> items) {
                    items.forEach(item -> item.render());
                }
            }
            """
                .trimIndent()

        withStore { store ->
            val producer = assertNotNull(ProducerRegistry.get("java-source"))
            producer.produce(
                IndexBuildContext.forInlineSources(
                    store = store,
                    commitHash = "abc",
                    sourceFiles = mapOf("Caller.java" to source),
                )
            )

            val symbols =
                store.prefixScan("sym:").map { it.second }.filterIsInstance<SymbolRecord>().toList()
            assertTrue(symbols.any { it.fqn == "sample.Caller#call" })
        }
    }

    @Test
    fun `typed Java lambda parameters expire after the lambda`() {
        val source =
            """
            package sample;
            import java.util.List;
            class First { void render() {} }
            class Second { void render() {} }
            class Caller {
                First item;
                void call(List<Second> items) {
                    items.forEach((Second item) -> item.render());
                    item.render();
                }
            }
            """
                .trimIndent()

        withStore { store ->
            val producer = assertNotNull(ProducerRegistry.get("java-source"))
            producer.produce(
                IndexBuildContext.forInlineSources(
                    store = store,
                    commitHash = "abc",
                    sourceFiles = mapOf("Caller.java" to source),
                )
            )

            val references =
                store
                    .prefixScan("ref:")
                    .map { it.second }
                    .filterIsInstance<ReferenceRecord>()
                    .toList()
            assertEquals(1, references.count { it.symbolFqn == "sample.Second#render" })
            assertEquals(1, references.count { it.symbolFqn == "sample.First#render" })
        }
    }

    private fun withStore(block: (XodusCodeIndexStore) -> Unit) {
        val store =
            XodusCodeIndexStore.open(createTempDirectory("java-source-producer-").resolve("index"))
        try {
            block(store)
        } finally {
            store.close()
        }
    }
}
