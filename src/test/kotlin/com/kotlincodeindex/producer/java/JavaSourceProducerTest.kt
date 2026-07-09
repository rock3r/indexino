package com.kotlincodeindex.producer.java

import com.kotlincodeindex.core.record.ReferenceRecord
import com.kotlincodeindex.core.record.SymbolRecord
import com.kotlincodeindex.core.xodus.XodusCodeIndexStore
import com.kotlincodeindex.producer.IndexBuildContext
import com.kotlincodeindex.producer.ProducerRegistry
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class JavaSourceProducerTest {
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
