package dev.sebastiano.indexino.producer

import dev.sebastiano.indexino.core.record.CodeIndexRecordCodec
import dev.sebastiano.indexino.core.store.CodeIndexStore
import dev.sebastiano.indexino.core.xodus.XodusCodeIndexStore
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * Representative Java, Kotlin, XML and multi-origin sources for write-path equivalence tests.
 * Fixture content is fixed so the committed golden stays byte-stable.
 */
internal class SortedBulkFixture
private constructor(
    val root: Path,
    private val files: List<FixtureFile>,
    val sources: List<IndexedSource>,
) {
    fun captureSnapshot(): SourceContentSnapshot = SourceContentSnapshot.capture(sources)

    fun produce(
        store: CodeIndexStore,
        changed: Collection<IndexedSource> = sources,
        deleted: Collection<IndexedSource> = emptyList(),
        snapshot: SourceContentSnapshot = captureSnapshot(),
        onPhase: () -> Unit = {},
    ) {
        val context =
            IndexBuildContext(
                store = store,
                commitHash = "sorted-bulk-fixture",
                sourceFiles = sources.map { source -> source.path },
                sources = sources,
                sourceSnapshot = snapshot,
                changedSourceSet = changed.toSet(),
                deletedSourceSet = deleted.toSet(),
            )
        ProducerRegistry.forApplications(emptyList()).forEach { producer ->
            onPhase()
            producer.produce(context, store)
        }
    }

    fun writeRevision(revision: Revision) {
        files.forEach { file ->
            val target = root.resolve(file.originId).resolve(file.path)
            target.parent?.createDirectories()
            target.writeText(file.content(revision))
        }
    }

    enum class Revision {
        INITIAL,
        EDITED,
    }

    private class FixtureFile(
        val originId: String,
        val path: String,
        val isCode: Boolean,
        val content: (Revision) -> String,
    )

    companion object {
        fun create(root: Path): SortedBulkFixture {
            Files.createDirectories(root)
            val files = fixtureFiles()
            return SortedBulkFixture(
                    root,
                    files,
                    files.map { file ->
                        IndexedSource(
                            file.originId,
                            root.resolve(file.originId),
                            file.path,
                            file.isCode,
                        )
                    },
                )
                .also { it.writeRevision(Revision.INITIAL) }
        }

        private fun fixtureFiles(): List<FixtureFile> =
            listOf(
                FixtureFile(
                    "app",
                    "build.gradle.kts",
                    isCode = false,
                    content = { "android {\n    namespace = \"com.example.app\"\n}\n" },
                ),
                FixtureFile(
                    "app",
                    "src/main/java/bench/Alpha.java",
                    isCode = true,
                    content = ::javaAlpha,
                ),
                FixtureFile(
                    "app",
                    "src/main/java/bench/Beta.java",
                    isCode = true,
                    content = { javaBeta },
                ),
                FixtureFile(
                    "app",
                    "src/main/kotlin/bench/Gamma.kt",
                    isCode = true,
                    content = ::kotlinGamma,
                ),
                FixtureFile(
                    "app",
                    "src/main/res/values/strings.xml",
                    isCode = false,
                    content = ::appStrings,
                ),
                FixtureFile(
                    "app",
                    "src/main/res/layout/main.xml",
                    isCode = false,
                    content = { layout },
                ),
                FixtureFile(
                    "ext",
                    "src/main/java/extbench/Delta.java",
                    isCode = true,
                    content = { javaDelta },
                ),
                FixtureFile(
                    "ext",
                    "src/main/res/values/strings.xml",
                    isCode = false,
                    content = { extStrings },
                ),
            )

        private fun javaAlpha(revision: Revision): String = buildString {
            appendLine("package bench;")
            appendLine()
            appendLine("import com.example.app.R;")
            appendLine()
            appendLine("public final class Alpha {")
            appendLine("    private final Beta beta = new Beta();")
            appendLine()
            appendLine("    public int run(int input) {")
            appendLine("        String label = getString(R.string.title);")
            appendLine("        int doubled = beta.doubleValue(input);")
            if (revision == Revision.EDITED) {
                appendLine("        return doubled + label.length() + helper(input);")
            } else {
                appendLine("        return doubled + label.length();")
            }
            appendLine("    }")
            appendLine("}")
        }

        private val javaBeta =
            """
            package bench;

            public final class Beta {
                public int doubleValue(int value) {
                    return value * 2;
                }
            }
            """
                .trimIndent() + "\n"

        private fun kotlinGamma(revision: Revision): String = buildString {
            appendLine("package bench")
            appendLine()
            appendLine("class Gamma(private val alpha: Alpha) {")
            appendLine("    fun combine(input: Int): Int = alpha.run(input) + helper(input)")
            appendLine()
            if (revision == Revision.EDITED) {
                appendLine("    fun extra(input: Int): Int = helper(input) * 2")
                appendLine()
            }
            appendLine("    private fun helper(value: Int): Int = value + 1")
            appendLine("}")
        }

        private fun appStrings(revision: Revision): String = buildString {
            appendLine("<resources>")
            appendLine("    <string name=\"title\">Title</string>")
            appendLine("    <color name=\"accent\">#FF00FF</color>")
            appendLine("    <style name=\"Theme\" parent=\"Base.Theme\">")
            appendLine("        <item name=\"colorAccent\">@color/accent</item>")
            appendLine("    </style>")
            if (revision == Revision.EDITED) {
                appendLine("    <string name=\"added\">Added @string/title</string>")
            }
            appendLine("</resources>")
        }

        private val layout =
            """
            <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android">
                <TextView android:id="@+id/title" android:text="@string/title" />
            </LinearLayout>
            """
                .trimIndent() + "\n"

        private val javaDelta =
            """
            package extbench;

            public final class Delta {
                public int compute(int value) {
                    return value * 3;
                }
            }
            """
                .trimIndent() + "\n"

        private val extStrings =
            """
            <resources>
                <string name="title">Ext title</string>
            </resources>
            """
                .trimIndent() + "\n"
    }
}

/** Full ordered dump of stored keys and encoded values; the storage contract in one string. */
internal fun dumpStoredFacts(store: CodeIndexStore): String = buildString {
    store.forEachPrefix("") { key, record ->
        append(key.value)
        append('\t')
        append(CodeIndexRecordCodec.encode(record).decodeToString())
        append('\n')
        true
    }
}

/** Builds the fixture into a fresh staging store and returns the stored key/value dump. */
internal fun buildFixtureFacts(
    root: Path,
    revision: SortedBulkFixture.Revision = SortedBulkFixture.Revision.INITIAL,
): String {
    val workspace = SortedBulkFixture.create(root)
    workspace.writeRevision(revision)
    val staging = root.resolve("staging").also { it.createDirectories() }
    return XodusCodeIndexStore.open(staging).use { store ->
        workspace.produce(store)
        dumpStoredFacts(store)
    }
}
