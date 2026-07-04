package com.kotlincodeindex.topology.bazel

import java.nio.file.FileVisitOption
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

data class BuildParseResult(
    val paths: List<String>,
    val warnings: List<String>,
)

object BuildFileParser {
    private val GLOB_CALL = Regex("""glob\s*\(""")
    private val SRCS_GLOB_START = Regex("""srcs\s*=\s*glob\s*\(""")
    private val QUOTED_STRING = Regex(""""([^"]+)"""")
    private val SRCS_LIST_START = Regex("""srcs\s*=\s*\[""")
    private val INCLUDE_NAMED = Regex("""include\s*=\s*\[([\s\S]*?)]""")
    private val INCLUDE_LIST = Regex("""^\s*\[([\s\S]*?)]""")
    private val EXCLUDE_LIST = Regex("""exclude\s*=\s*\[([\s\S]*?)]""")
    private val EXCLUDE_GLOB = Regex("""exclude\s*=\s*glob\s*\(\s*\[([\s\S]*?)]""")

    fun parseKotlinSources(buildFile: Path, workspaceRoot: Path): BuildParseResult {
        val packageDir = checkNotNull(buildFile.parent) { "BUILD file has no parent: $buildFile" }
        val packageRelative = workspaceRoot.relativize(packageDir).toString().replace('\\', '/')
        val content = buildFile.readText()
        val paths = linkedSetOf<String>()
        val warnings = mutableListOf<String>()

        for (spec in extractGlobSpecs(content)) {
            val excluded = spec.excludes.flatMap { expandGlob(packageDir, it) }.toSet()
            val included = spec.includes.flatMap { expandGlob(packageDir, it) }
                .filterNot { it in excluded }
                .toMutableSet()

            for (pattern in spec.includes) {
                if (!pattern.contains(".kt")) {
                    continue
                }
                val ktMatches = expandGlob(packageDir, pattern)
                    .filter { it.endsWith(".kt") && it !in excluded }
                if (ktMatches.isEmpty()) {
                    warnings += "BUILD glob '$pattern' under $packageRelative matched no .kt files"
                }
            }

            included.filter { it.endsWith(".kt") }.forEach { relative ->
                paths += "$packageRelative/$relative"
            }
        }

        for (literal in extractLiteralSrcs(content)) {
            if (literal.endsWith(".kt") && !literal.contains('*')) {
                paths += "$packageRelative/$literal"
            }
        }

        return BuildParseResult(paths.toList(), warnings)
    }

    private data class GlobSpec(
        val includes: List<String>,
        val excludes: List<String>,
    )

    private fun extractGlobSpecs(content: String): List<GlobSpec> =
        SRCS_GLOB_START.findAll(content)
            .filterNot { match -> isCommentedOutInBlock(content, match.range.first) }
            .mapNotNull { match ->
                val openParen = match.range.last
                val body = extractBalancedParenBody(content, openParen) ?: return@mapNotNull null
                val includes = extractIncludePatterns(body)
                val excludes = buildList {
                    EXCLUDE_LIST.findAll(body).forEach { exclude ->
                        addAll(QUOTED_STRING.findAll(exclude.groupValues[1]).map { it.groupValues[1] })
                    }
                    EXCLUDE_GLOB.findAll(body).forEach { exclude ->
                        addAll(QUOTED_STRING.findAll(exclude.groupValues[1]).map { it.groupValues[1] })
                    }
                }
                GlobSpec(includes, excludes)
            }
            .toList()

    private fun extractIncludePatterns(body: String): List<String> {
        val includeBody = INCLUDE_NAMED.find(body)?.groupValues?.get(1)
            ?: INCLUDE_LIST.find(body)?.groupValues?.get(1)
            ?: return emptyList()
        return QUOTED_STRING.findAll(includeBody).map { it.groupValues[1] }.toList()
    }

    private fun extractBalancedParenBody(content: String, openParenIndex: Int): String? {
        val endIndex = findBalancedParenEnd(content, openParenIndex) ?: return null
        return content.substring(openParenIndex + 1, endIndex)
    }

    private fun findBalancedParenEnd(content: String, openParenIndex: Int): Int? {
        if (openParenIndex !in content.indices || content[openParenIndex] != '(') {
            return null
        }
        var depth = 0
        for (index in openParenIndex until content.length) {
            when (content[index]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) {
                        return index
                    }
                }
            }
        }
        return null
    }

    private fun extractLiteralSrcs(content: String): List<String> =
        SRCS_LIST_START.findAll(content).flatMap { match ->
            val openBracket = match.range.last
            val blockBody = extractBalancedBracketBody(content, openBracket) ?: return@flatMap emptySequence()
            val globRangesInBlock = GLOB_CALL.findAll(blockBody).mapNotNull { globMatch ->
                val openParen = globMatch.range.last
                val endIndex = findBalancedParenEnd(blockBody, openParen) ?: return@mapNotNull null
                globMatch.range.first..endIndex
            }
            QUOTED_STRING.findAll(blockBody)
                .filter { quoted ->
                    globRangesInBlock.none { quoted.range.first in it } &&
                        !isCommentedOutInBlock(blockBody, quoted.range.first)
                }
                .map { it.groupValues[1] }
        }.toList()

    private fun isCommentedOutInBlock(blockBody: String, index: Int): Boolean {
        val lineStart = blockBody.lastIndexOf('\n', index - 1) + 1
        val lineEnd = blockBody.indexOf('\n', index).let { if (it < 0) blockBody.length else it }
        val line = blockBody.substring(lineStart, lineEnd)
        val relativeIndex = index - lineStart

        var inString = false
        var cursor = 0
        while (cursor < relativeIndex) {
            when (line[cursor]) {
                '"' -> inString = !inString
                '#' -> if (!inString) {
                    return true
                }
            }
            cursor++
        }
        return false
    }

    private fun extractBalancedBracketBody(content: String, openBracketIndex: Int): String? {
        val endIndex = findBalancedBracketEnd(content, openBracketIndex) ?: return null
        return content.substring(openBracketIndex + 1, endIndex)
    }

    private fun findBalancedBracketEnd(content: String, openBracketIndex: Int): Int? {
        if (openBracketIndex !in content.indices || content[openBracketIndex] != '[') {
            return null
        }
        var depth = 0
        for (index in openBracketIndex until content.length) {
            when (content[index]) {
                '[' -> depth++
                ']' -> {
                    depth--
                    if (depth == 0) {
                        return index
                    }
                }
            }
        }
        return null
    }

    private fun expandGlob(packageDir: Path, pattern: String): List<String> {
        if (!pattern.contains('*') && !pattern.contains('?')) {
            val file = packageDir.resolve(pattern)
            return if (file.isRegularFile()) listOf(pattern) else emptyList()
        }
        if (!packageDir.exists()) {
            return emptyList()
        }
        val patterns = bazelGlobPatternVariants(pattern)
        val matchers = patterns.map { packageDir.fileSystem.getPathMatcher("glob:$it") }
        return Files.walk(packageDir, FileVisitOption.FOLLOW_LINKS).use { stream ->
            stream.filter { it.isRegularFile() }
                .filter { !isInSubpackage(packageDir, it) }
                .map { packageDir.relativize(it).toString().replace('\\', '/') }
                .filter { relative -> matchers.any { it.matches(Path.of(relative)) } }
                .sorted()
                .toList()
        }
    }

    private fun bazelGlobPatternVariants(pattern: String): List<String> {
        if (!pattern.contains("**/")) {
            return listOf(pattern)
        }
        val variants = linkedSetOf(pattern)
        var searchFrom = 0
        while (true) {
            val start = pattern.indexOf("**/", searchFrom)
            if (start < 0) {
                break
            }
            variants += pattern.removeRange(start, start + 3)
            searchFrom = start + 1
        }
        return variants.toList()
    }

    private fun isInSubpackage(packageDir: Path, file: Path): Boolean {
        var current = file.parent
        while (current != null && current != packageDir) {
            if (hasBuildFile(current)) {
                return true
            }
            current = current.parent
        }
        return false
    }

    private fun hasBuildFile(packageDir: Path): Boolean =
        packageDir.resolve("BUILD.bazel").isRegularFile() ||
            packageDir.resolve("BUILD").isRegularFile()
}
