package com.kotlincodeindex.topology.bazel

import java.nio.file.Path
import kotlin.io.path.readText

data class BuildParseResult(val paths: List<String>, val warnings: List<String>)

object BuildFileParser {
    private val GLOB_CALL = Regex("""glob\s*\(""")
    private val SRCS_GLOB_START = Regex("""srcs\s*=\s*glob\s*\(""")
    private val QUOTED_STRING = Regex(""""([^"]+)"""")
    private val SINGLE_QUOTED_STRING = Regex("""'([^']+)'""")
    private val SRCS_ASSIGNMENT = Regex("""srcs\s*=""")
    private val CONCAT_PLUS_GLOB = Regex("""\+\s*glob\s*\(""")
    private val EXCLUDE_GLOB_START = Regex("""exclude\s*=\s*glob\s*\(""")
    private val POSITIONAL_INCLUDE = Regex("""^\[\s*([\s\S]*?)]""")
    private val INCLUDE_NAMED = Regex("""include\s*=\s*\[([\s\S]*?)]""")
    private val INCLUDE_LIST = Regex("""\[\s*([\s\S]*?)]""")
    private val EXCLUDE_LIST = Regex("""exclude\s*=\s*\[([\s\S]*?)]""")
    private val SRCS_LIST_START = Regex("""srcs\s*=\s*\[""")
    private val RESOURCE_FILES_GLOB_START = Regex("""resource_files\s*=\s*glob\s*\(""")
    private val RESOURCE_FILES_LIST_START = Regex("""resource_files\s*=\s*\[""")
    private val INDEXABLE_EXTENSIONS = setOf("kt", "java", "xml")

    fun parseKotlinSources(buildFile: Path, workspaceRoot: Path): BuildParseResult {
        val packageDir = checkNotNull(buildFile.parent) { "BUILD file has no parent: $buildFile" }
        val packageRelative = workspaceRoot.relativize(packageDir).toString().replace('\\', '/')
        val content = buildFile.readText()
        val paths = linkedSetOf<String>()
        val warnings = mutableListOf<String>()

        for (spec in extractGlobSpecs(content)) {
            val excluded =
                spec.excludes.flatMap { BuildFileGlob.expandGlob(packageDir, it) }.toSet()
            val included =
                spec.includes
                    .flatMap { BuildFileGlob.expandGlob(packageDir, it) }
                    .filterNot { it in excluded }
                    .toMutableSet()

            for (pattern in spec.includes) {
                if (INDEXABLE_EXTENSIONS.none { extension -> pattern.contains(".$extension") }) {
                    continue
                }
                val sourceMatches =
                    BuildFileGlob.expandGlob(packageDir, pattern).filter {
                        isIndexablePath(it) && it !in excluded
                    }
                if (sourceMatches.isEmpty()) {
                    warnings +=
                        "BUILD glob '$pattern' under $packageRelative matched no indexable files"
                }
            }

            included.filter(::isIndexablePath).forEach { relative ->
                paths += "$packageRelative/$relative"
            }
        }

        for (literal in extractLiteralSrcs(content)) {
            if (isIndexablePath(literal) && !literal.contains('*')) {
                paths += "$packageRelative/$literal"
            }
        }

        for (relative in extractResourceFiles(content, packageDir)) {
            paths += "$packageRelative/$relative"
        }

        return BuildParseResult(paths.toList(), warnings)
    }

    private data class GlobSpec(val includes: List<String>, val excludes: List<String>)

    private fun extractGlobSpecs(content: String): List<GlobSpec> =
        findSrcsGlobOpenParens(content)
            .mapNotNull { openParen ->
                val body = extractBalancedParenBody(content, openParen) ?: return@mapNotNull null
                val includes = extractIncludePatterns(body)
                val excludes = extractExcludePatterns(body)
                GlobSpec(includes, excludes)
            }
            .toList()

    private fun findSrcsGlobOpenParens(content: String): List<Int> {
        val openParens = linkedSetOf<Int>()
        SRCS_GLOB_START.findAll(content)
            .filterNot { match ->
                BuildFileComments.isCommentedOutInBlock(content, match.range.first)
            }
            .forEach { openParens += it.range.last }
        SRCS_ASSIGNMENT.findAll(content)
            .filterNot { match ->
                BuildFileComments.isCommentedOutInBlock(content, match.range.first)
            }
            .forEach { match ->
                val valueStart = match.range.last + 1
                val valueEnd =
                    BuildFileSrcsParser.findSrcsValueEnd(content, valueStart) ?: return@forEach
                val assignment = content.substring(valueStart, valueEnd)
                CONCAT_PLUS_GLOB.findAll(assignment).forEach { concat ->
                    val absIndex = valueStart + concat.range.first
                    if (!BuildFileComments.isCommentedOutInBlock(content, absIndex)) {
                        openParens += valueStart + concat.range.last
                    }
                }
            }
        return openParens.toList()
    }

    private fun extractExcludePatterns(body: String): List<String> = buildList {
        EXCLUDE_LIST.findAll(body).forEach { exclude ->
            addAll(extractQuotedPatterns(exclude.groupValues[1]))
        }
        EXCLUDE_GLOB_START.findAll(body).forEach { match ->
            val openParen = match.range.last
            val excludeBody = extractBalancedParenBody(body, openParen) ?: return@forEach
            addAll(extractIncludePatterns(excludeBody))
        }
    }

    private fun extractIncludePatterns(body: String): List<String> {
        val excludeGlobRanges = findExcludeGlobBodyRanges(body)
        fun isInExcludeGlob(index: Int) = excludeGlobRanges.any { index in it }

        POSITIONAL_INCLUDE.find(body.trimStart())?.let { match ->
            if (!BuildFileComments.isCommentedOutInBlock(body.trimStart(), match.range.first)) {
                return extractQuotedPatterns(match.groupValues[1])
            }
        }
        INCLUDE_NAMED.findAll(body)
            .firstOrNull { match ->
                !BuildFileComments.isCommentedOutInBlock(body, match.range.first) &&
                    !isInExcludeGlob(match.range.first)
            }
            ?.let { match ->
                return extractQuotedPatterns(match.groupValues[1])
            }
        INCLUDE_LIST.findAll(body)
            .firstOrNull { match ->
                !BuildFileComments.isCommentedOutInBlock(body, match.range.first) &&
                    !isInExcludeGlob(match.range.first) &&
                    !isExcludeListBracket(body, match.range.first)
            }
            ?.let { match ->
                return extractQuotedPatterns(match.groupValues[1])
            }
        return emptyList()
    }

    private fun isExcludeListBracket(body: String, index: Int): Boolean {
        val prefix = body.substring(0, index).trimEnd()
        return prefix.endsWith("exclude =") || prefix.endsWith("exclude=")
    }

    private fun findExcludeGlobBodyRanges(body: String): List<IntRange> =
        EXCLUDE_GLOB_START.findAll(body)
            .mapNotNull { match ->
                val openParen = match.range.last
                val endIndex = findBalancedParenEnd(body, openParen) ?: return@mapNotNull null
                (openParen + 1) until endIndex
            }
            .toList()

    private fun extractQuotedPatterns(text: String): List<String> = buildList {
        (QUOTED_STRING.findAll(text) + SINGLE_QUOTED_STRING.findAll(text))
            .filterNot { BuildFileComments.isCommentedOutInBlock(text, it.range.first) }
            .forEach { add(it.groupValues[1]) }
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
        SRCS_LIST_START.findAll(content)
            .flatMap { match ->
                val openBracket = match.range.last
                val blockBody =
                    extractBalancedBracketBody(content, openBracket)
                        ?: return@flatMap emptySequence()
                val globRangesInBlock =
                    GLOB_CALL.findAll(blockBody).mapNotNull { globMatch ->
                        val openParen = globMatch.range.last
                        val endIndex =
                            findBalancedParenEnd(blockBody, openParen) ?: return@mapNotNull null
                        globMatch.range.first..endIndex
                    }
                val quotedLiterals =
                    QUOTED_STRING.findAll(blockBody) + SINGLE_QUOTED_STRING.findAll(blockBody)
                quotedLiterals
                    .filter { quoted ->
                        globRangesInBlock.none { quoted.range.first in it } &&
                            !BuildFileComments.isCommentedOutInBlock(blockBody, quoted.range.first)
                    }
                    .map { it.groupValues[1] }
            }
            .toList()

    private fun extractResourceFiles(content: String, packageDir: Path): List<String> = buildList {
        RESOURCE_FILES_GLOB_START.findAll(content)
            .filterNot { BuildFileComments.isCommentedOutInBlock(content, it.range.first) }
            .forEach { match ->
                val body = extractBalancedParenBody(content, match.range.last) ?: return@forEach
                val excluded =
                    extractExcludePatterns(body)
                        .flatMap { BuildFileGlob.expandGlob(packageDir, it) }
                        .toSet()
                extractIncludePatterns(body)
                    .flatMap { BuildFileGlob.expandGlob(packageDir, it) }
                    .filter { it.endsWith(".xml") && it !in excluded }
                    .forEach(::add)
            }
        RESOURCE_FILES_LIST_START.findAll(content)
            .filterNot { BuildFileComments.isCommentedOutInBlock(content, it.range.first) }
            .forEach { match ->
                val body = extractBalancedBracketBody(content, match.range.last) ?: return@forEach
                extractQuotedPatterns(body).filter { it.endsWith(".xml") }.forEach(::add)
            }
    }

    private fun isIndexablePath(path: String): Boolean =
        path.substringAfterLast('.', "") in INDEXABLE_EXTENSIONS

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
}
