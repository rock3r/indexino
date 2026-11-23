package dev.sebastiano.indexino.producer.java

import dev.sebastiano.indexino.core.key.CodeIndexKey
import dev.sebastiano.indexino.core.record.CallSiteRecord
import dev.sebastiano.indexino.core.record.CodeIndexRecord
import dev.sebastiano.indexino.core.record.CodeIndexRecordCodec
import dev.sebastiano.indexino.core.record.ReferenceRecord
import dev.sebastiano.indexino.core.record.ResourceDefinitionRecord
import dev.sebastiano.indexino.core.record.ResourceUsageRecord
import dev.sebastiano.indexino.core.record.SymbolRecord
import dev.sebastiano.indexino.core.store.CodeIndexStore
import dev.sebastiano.indexino.core.xodus.XodusCodeIndexStore
import dev.sebastiano.indexino.producer.IndexBuildContext
import dev.sebastiano.indexino.producer.ProducerRegistry
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class JavaSourceProducerContractTest {
    @Test
    fun `emits the same records as the two pass baseline on multi file fixtures`() {
        withStore { store ->
            seedResourceDefinitions(store)
            javaProducer().produce(IndexBuildContext.forInlineSources(store, "contract", sources()))

            val actual =
                store
                    .prefixScan("")
                    .filter { (_, record) -> record.languageNamespace() }
                    .map { (key, record) -> encodedLine(key, record) }
                    .toList()
                    .sorted()
            val golden = Path.of(GOLDEN_PATH)
            assertTrue(Files.exists(golden), "Missing baseline golden: $golden")
            assertEquals(Files.readAllLines(golden).filter(String::isNotBlank), actual)
        }
    }

    @Test
    fun `resolves cross file parameter names without symbol store scans`() {
        val symbolScans = AtomicInteger()
        withStore { delegate ->
            val store =
                object : CodeIndexStore by delegate {
                    override fun prefixScan(
                        prefix: String
                    ): Sequence<Pair<CodeIndexKey, CodeIndexRecord>> {
                        if (prefix.startsWith("sym:")) symbolScans.incrementAndGet()
                        return delegate.prefixScan(prefix)
                    }

                    override fun forEachPrefix(
                        prefix: String,
                        action: (CodeIndexKey, CodeIndexRecord) -> Boolean,
                    ) {
                        if (prefix.startsWith("sym:")) symbolScans.incrementAndGet()
                        delegate.forEachPrefix(prefix, action)
                    }

                    override fun forEachWritablePrefix(
                        prefix: String,
                        action: (CodeIndexKey, CodeIndexRecord) -> Boolean,
                    ) = delegate.forEachWritablePrefix(prefix, action)
                }
            javaProducer()
                .produce(
                    IndexBuildContext.forInlineSources(
                        store,
                        "store-reads",
                        linkedMapOf(
                            "Caller.java" to
                                "class Caller { int run(int supplied) { " +
                                    "return Target.take(supplied); } }",
                            "Target.java" to
                                "class Target { static int take(int expected) { " +
                                    "return expected; } }",
                        ),
                    )
                )
            assertEquals(0, symbolScans.get(), "Candidates still query symbol store reads")
        }
    }

    @Test
    fun `resolves parameter names from unchanged files on incremental runs`() {
        withStore { store ->
            val producer = javaProducer()
            val initial =
                linkedMapOf(
                    "Caller.java" to
                        "class Caller { int run(int supplied) { " +
                            "return Target.take(supplied); } }",
                    "Target.java" to
                        "class Target { static int take(int expected) { return expected; } }",
                )
            producer.produce(IndexBuildContext.forInlineSources(store, "initial", initial))
            val updated =
                initial +
                    ("Caller.java" to
                        "class Caller { int run(int supplied) { return Target.take(supplied); } " +
                            "int again(int value) { return Target.take(value); } }")
            producer.produce(
                IndexBuildContext(
                    store = store,
                    commitHash = "incremental",
                    sourceFiles = updated.keys.toList(),
                    sourceContentOverrides = updated,
                    changedSourceFiles = setOf("Caller.java"),
                )
            )

            val calls =
                store
                    .prefixScan("call:")
                    .map { it.second }
                    .filterIsInstance<CallSiteRecord>()
                    .toList()
            assertEquals(2, calls.size)
            assertEquals(
                listOf("expected", "expected"),
                calls
                    .sortedBy { it.enclosingSymbolFqn }
                    .map { call -> call.arguments.single().resolvedName },
            )
        }
    }

    private fun javaProducer() = assertNotNull(ProducerRegistry.get("java-source"))

    private fun CodeIndexRecord.languageNamespace(): Boolean =
        this is SymbolRecord ||
            this is ReferenceRecord ||
            this is CallSiteRecord ||
            this is ResourceUsageRecord

    private fun encodedLine(key: CodeIndexKey, record: CodeIndexRecord): String =
        key.value + "\t" + CodeIndexRecordCodec.encode(record).decodeToString()

    private fun seedResourceDefinitions(store: XodusCodeIndexStore) {
        store.put(
            CodeIndexKey.resourceDefinition(
                packageName = "com.example.wild",
                type = "color",
                name = "wildcard_accent",
                qualifiers = "",
                originId = "workspace",
                relativeFile = "app/src/main/res/values/colors.xml",
                line = 2,
                column = 1,
            ),
            ResourceDefinitionRecord(
                packageName = "com.example.wild",
                type = "color",
                name = "wildcard_accent",
                qualifiers = "",
                relativeFile = "app/src/main/res/values/colors.xml",
                line = 2,
            ),
        )
    }

    companion object {
        const val GOLDEN_PATH =
            "src/test/resources/fixtures/java-producer-contract/expected-records.txt"

        fun sources(): LinkedHashMap<String, String> =
            linkedMapOf<String, String>().apply {
                put("app/build.gradle.kts", "android { namespace = \"com.example.namespace\" }")
                putAll(declarationSources())
                putAll(helperSources())
                putAll(resourceSources())
            }

        private fun declarationSources(): Map<String, String> =
            linkedMapOf(
                "app/src/main/java/sample/Caller.java" to
                    """
                    package sample;

                    import sample.util.Util;
                    import static sample.Constants.shared;
                    import static com.example.wild.R.color.*;
                    import java.util.List;

                    public class Caller extends Base {
                        private Target target = new Target();
                        private Overloads overloads = new Overloads();
                        private int wildcardColor = wildcard_accent;

                        void run(List<Target> items) {
                            inherited();
                            unrelated();
                            target.render(items.size());
                            new Target().render(1);
                            Util.staticCall(target, "label");
                            shared();
                            overloads.format("value");
                            overloads.format(42);
                            int taken = Target.take(items.size());
                            items.forEach(item -> item.render(0));
                            var local = new Target();
                            local.render(2);
                            Runnable task = new Runnable() {
                                public void run() { target.render(3); }
                            };
                        }
                    }
                    """
                        .trimIndent(),
                "app/src/main/java/sample/Base.java" to
                    """
                    package sample;

                    public class Base {
                        protected void inherited() {}
                    }
                    """
                        .trimIndent(),
                "app/src/main/java/sample/Target.java" to
                    """
                    package sample;

                    public class Target {
                        public Target() {}

                        public void render(int count) {}

                        public static int take(int expected) { return expected; }
                    }
                    """
                        .trimIndent(),
                "app/src/main/java/sample/Child.java" to
                    """
                    package sample;

                    class Child extends Base {
                        void call() {
                            inherited();
                            unrelated();
                        }
                    }
                    """
                        .trimIndent(),
            )

        private fun helperSources(): Map<String, String> =
            linkedMapOf(
                "app/src/main/java/sample/Overloads.java" to
                    """
                    package sample;

                    public class Overloads {
                        void format(String text) {}

                        void format(int number) {}
                    }
                    """
                        .trimIndent(),
                "app/src/main/java/sample/Constants.java" to
                    """
                    package sample;

                    public class Constants {
                        public static String shared() { return "shared"; }
                    }
                    """
                        .trimIndent(),
                "app/src/main/java/sample/util/Util.java" to
                    """
                    package sample.util;

                    public class Util {
                        public static int staticCall(Object receiver, String label) {
                            return label.length();
                        }
                    }
                    """
                        .trimIndent(),
            )

        private fun resourceSources(): Map<String, String> =
            linkedMapOf(
                "app/src/main/java/com/example/app/Screen.java" to
                    """
                    package com.example.app;

                    import com.example.feature.R;

                    class Screen {
                        int title = R.string.title;
                        int titleLength = R.string.title.length();
                        int styleable = R.styleable.CustomView;
                        int icon = com.example.assets.R.drawable.icon;
                        int notResource = foo.R.state.ordinal();
                        int wildcard = wildcard_accent;
                    }
                    """
                        .trimIndent(),
                "app/src/main/java/com/example/app/LocalScreen.java" to
                    """
                    package com.example.app;

                    class LocalScreen {
                        int local = R.string.local_title;
                    }
                    """
                        .trimIndent(),
            )
    }

    private fun withStore(block: (XodusCodeIndexStore) -> Unit) {
        val store = XodusCodeIndexStore.open(createTempDirectory("java-contract-").resolve("index"))
        try {
            block(store)
        } finally {
            store.close()
        }
    }
}
