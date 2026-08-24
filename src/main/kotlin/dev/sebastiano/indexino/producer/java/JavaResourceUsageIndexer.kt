package dev.sebastiano.indexino.producer.java

import com.sun.source.tree.IdentifierTree
import com.sun.source.tree.ImportTree
import com.sun.source.tree.MemberSelectTree
import com.sun.source.tree.MethodInvocationTree
import com.sun.source.tree.MethodTree
import com.sun.source.tree.Tree
import com.sun.source.tree.VariableTree
import com.sun.source.util.TreePath
import dev.sebastiano.indexino.core.key.CodeIndexKey
import dev.sebastiano.indexino.core.record.ResourceDefinitionRecord
import dev.sebastiano.indexino.core.record.ResourceUsageRecord
import dev.sebastiano.indexino.core.store.CodeIndexStore
import dev.sebastiano.indexino.producer.xml.ResourceMetadata

internal class JavaResourceUsageIndexer(
    private val originId: String,
    private val relativePath: String,
    private val defaultResourcePackage: String?,
    private val packageName: String,
    private val store: CodeIndexStore,
    private val imports: Map<String, String>,
    private val staticImports: Map<String, String>,
    private val staticWildcardImports: List<String>,
    private val variableScopes: ArrayDeque<MutableMap<String, String>>,
    private val positionOf: (Tree) -> SourcePosition,
) {
    fun indexStaticResourceUsage(node: IdentifierTree, path: TreePath) {
        val parent = path.parentPath?.leaf
        if (shouldSkipStaticResourceIdentifier(node, parent, path)) return
        val name = node.name.toString()
        if (variableScopes.reversed().any { scope -> name in scope }) return
        if (isClassResourceChain(node, parent, path)) return
        val explicitOwner = staticImports[name]
        val owners =
            if (explicitOwner != null) listOf(explicitOwner) else staticWildcardImports.distinct()
        if (owners.isEmpty()) return
        val start = positionOf(node)
        owners.forEach { owner ->
            putStaticResourceUsage(owner, name, start, explicitOwner == null)
        }
    }

    fun indexResourceUsage(node: MemberSelectTree, path: TreePath) {
        if (generateSequence(path.parentPath) { it.parentPath }.any { it.leaf is ImportTree }) {
            return
        }
        val segments = memberSelectSegments(node) ?: return
        val rIndex =
            segments.indices.firstOrNull { index ->
                index + 2 <= segments.lastIndex &&
                    (segments[index] == "R" ||
                        imports[segments[index]]?.substringAfterLast('.') == "R") &&
                    segments[index + 1] in ResourceMetadata.RESOURCE_TYPES
            } ?: return
        val explicitPackage = segments.take(rIndex).joinToString(".").ifBlank { null }
        val importedOwner = imports[segments[rIndex]]
        val resourcePackage =
            explicitPackage
                ?: importedOwner?.substringBeforeLast('.', "")?.takeIf(String::isNotBlank)
                ?: defaultResourcePackage
                ?: packageName.ifBlank { null }
        val type = segments[rIndex + 1]
        val name = segments[rIndex + 2]
        val start = positionOf(node)
        store.put(
            CodeIndexKey.resourceUsage(
                packageName = resourcePackage,
                type = type,
                name = name,
                originId = originId,
                relativeFile = relativePath,
                line = start.line,
                column = start.column,
            ),
            ResourceUsageRecord(
                packageName = resourcePackage,
                type = type,
                name = name,
                relativeFile = relativePath,
                line = start.line,
                column = start.column,
                offset = start.offset,
                language = LANGUAGE,
                originId = originId,
            ),
        )
    }

    private fun shouldSkipStaticResourceIdentifier(
        node: IdentifierTree,
        parent: Tree?,
        path: TreePath,
    ): Boolean =
        generateSequence(path.parentPath) { it.parentPath }.any { it.leaf is ImportTree } ||
            (parent is MemberSelectTree && parent.expression != node) ||
            (parent is MethodInvocationTree && parent.methodSelect == node) ||
            (parent is MemberSelectTree &&
                parent.identifier.toString() in ResourceMetadata.RESOURCE_TYPES) ||
            (parent as? VariableTree)?.name == node.name ||
            (parent as? MethodTree)?.name == node.name

    private fun isClassResourceChain(node: IdentifierTree, parent: Tree?, path: TreePath): Boolean {
        if (parent !is MemberSelectTree || parent.expression != node) return false
        val outermost =
            generateSequence(path.parentPath) { it.parentPath }
                .map { it.leaf }
                .filterIsInstance<MemberSelectTree>()
                .lastOrNull()
        val segments = outermost?.let(::memberSelectSegments)
        return segments != null && ("R" in segments || "Res" in segments)
    }

    private fun putStaticResourceUsage(
        owner: String,
        name: String,
        start: SourcePosition,
        requireDefinition: Boolean,
    ) {
        val marker = ".R."
        if (marker !in owner) return
        val resourcePackage = owner.substringBefore(marker)
        val type = owner.substringAfter(marker)
        if ('.' in type || type !in ResourceMetadata.RESOURCE_TYPES) return
        if (requireDefinition && !hasResourceDefinition(resourcePackage, type, name)) return
        store.put(
            CodeIndexKey.resourceUsage(
                packageName = resourcePackage,
                type = type,
                name = name,
                originId = originId,
                relativeFile = relativePath,
                line = start.line,
                column = start.column,
            ),
            ResourceUsageRecord(
                packageName = resourcePackage,
                type = type,
                name = name,
                relativeFile = relativePath,
                line = start.line,
                column = start.column,
                offset = start.offset,
                language = LANGUAGE,
                originId = originId,
            ),
        )
    }

    private fun hasResourceDefinition(
        resourcePackage: String,
        type: String,
        name: String,
    ): Boolean {
        var found = false
        store.forEachPrefix("resdef:$resourcePackage:$type:$name:") { _, record ->
            if (
                record is ResourceDefinitionRecord &&
                    record.packageName == resourcePackage &&
                    record.type == type &&
                    record.name == name
            ) {
                found = true
            }
            !found
        }
        return found
    }

    private fun memberSelectSegments(tree: Tree): List<String>? =
        when (tree) {
            is IdentifierTree -> listOf(tree.name.toString())
            is MemberSelectTree ->
                memberSelectSegments(tree.expression)?.plus(tree.identifier.toString())
            else -> null
        }

    internal data class SourcePosition(val line: Int, val column: Int, val offset: Int)

    private companion object {
        const val LANGUAGE = "java"
    }
}
