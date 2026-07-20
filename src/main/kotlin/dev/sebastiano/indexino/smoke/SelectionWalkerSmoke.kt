package dev.sebastiano.indexino.smoke

import dev.sebastiano.indexino.application.selectioncontext.SelectionWalker
import dev.sebastiano.indexino.parse.KotlinPsiParser
import java.nio.file.Paths
import kotlin.io.path.exists
import kotlin.io.path.readText

/**
 * One-off smoke runner for SelectionWalker against intellij-community sources.
 *
 *     ./gradlew run --args="smoke /path/to/intellij-community"
 */
internal fun main(args: Array<String>) {
    val intellijRoot =
        when {
            args.size >= 2 && args[0] == "smoke" -> Paths.get(args[1])
            args.isNotEmpty() -> Paths.get(args[0])
            else -> Paths.get("../intellij-community").toAbsolutePath().normalize()
        }
    require(intellijRoot.exists()) { "intellij-community not found at $intellijRoot" }

    val cases =
        listOf(
            SmokeCase(
                relative = "plugins/devkit/intellij.devkit.compose/src/showcase/ComposeShowcase.kt",
                line = 223,
                label = "Text inside SelectionContainer (SelectableText)",
            ),
            SmokeCase(
                relative = "plugins/devkit/intellij.devkit.compose/src/showcase/ComposeShowcase.kt",
                line = 212,
                label = "Text outside SelectionContainer (Label)",
            ),
            SmokeCase(
                relative =
                    "platform/jewel/ui/src/main/kotlin/org/jetbrains/jewel/ui/component/Popup.kt",
                line = 219,
                label = "ComposePopup inside DisableSelection",
            ),
            SmokeCase(
                relative =
                    "platform/jewel/markdown/core/src/main/kotlin/" +
                        "org/jetbrains/jewel/markdown/rendering/DefaultMarkdownBlockRenderer.kt",
                line = 740,
                label = "Text inside DisableSelection (FencedBlockInfo)",
            ),
            SmokeCase(
                relative =
                    "platform/jewel/markdown/core/src/main/kotlin/" +
                        "org/jetbrains/jewel/markdown/Markdown.kt",
                line = 308,
                label =
                    "RenderBlock inside LazyColumn lambda " +
                        "(MaybeSelectable content — indirect SC)",
            ),
            SmokeCase(
                relative =
                    "platform/jewel/markdown/core/src/main/kotlin/org/jetbrains/jewel/markdown/Markdown.kt",
                line = 337,
                label = "movableContent inside conditional SelectionContainer (direct SC)",
            ),
        )

    KotlinPsiParser().use { parser ->
        val walker = SelectionWalker()
        for (case in cases) {
            val path = intellijRoot.resolve(case.relative)
            require(path.exists()) { "Missing: $path" }
            val content = path.readText()
            val file = parser.parseFile(case.relative, content)
            val ctx = walker.findCallAtLine(file, case.relative, case.line)
            println(
                buildString {
                    appendLine("=== ${case.label} ===")
                    appendLine("  file: ${case.relative}:${case.line}")
                    appendLine("  callee: ${ctx.callee}")
                    appendLine("  inSelectionContainer: ${ctx.inSelectionContainer}")
                    appendLine("  selectionContainerCount: ${ctx.selectionContainerCount}")
                    appendLine("  excludedByDisableSelection: ${ctx.excludedByDisableSelection}")
                    if (ctx.selectionContainers.isNotEmpty()) {
                        appendLine("  selectionContainers: ${ctx.selectionContainers}")
                    }
                    if (ctx.disableSelection != null) {
                        appendLine("  disableSelection: ${ctx.disableSelection}")
                    }
                }
            )
        }
    }
}

private data class SmokeCase(val relative: String, val line: Int, val label: String)
