package dev.sebastiano.indexino.application.selectioncontext

import dev.sebastiano.indexino.application.selectioncontext.model.DisableSelectionInfo
import dev.sebastiano.indexino.application.selectioncontext.model.SelectionContainerInfo
import dev.sebastiano.indexino.application.selectioncontext.model.SelectionContext
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtSimpleNameExpression

internal class SelectionWalker(
    private val selectionContainerNames: Set<String> = DEFAULT_SELECTION_CONTAINER_NAMES,
    private val disableSelectionNames: Set<String> = DEFAULT_DISABLE_SELECTION_NAMES,
    private val composableAnnotation: String = COMPOSABLE_ANNOTATION,
    private val knownWrapperRules: List<KnownWrapperRule> = KnownWrapperConfig.load(),
) {
    fun analyzeCallSite(call: KtCallExpression, relativeFile: String): SelectionContext {
        val callee = extractCalleeName(call)
        val enclosingComposable = findEnclosingComposable(call, relativeFile)

        val file = call.containingKtFile
        val scAliases = resolveAliases(file, selectionContainerNames)
        val dsAliases = resolveAliases(file, disableSelectionNames)

        val containers = mutableListOf<SelectionContainerInfo>()
        var disableSelection: DisableSelectionInfo? = null
        var excluded = false
        var lambdaOrigin = false
        val walkState =
            AncestorWalkState(
                excluded = excluded,
                disableSelection = disableSelection,
                lambdaOrigin = lambdaOrigin,
            )

        var node = call.parent
        while (node != null && node != enclosingComposable) {
            val callExpr = node as? KtCallExpression
            if (callExpr == null) {
                if (node is org.jetbrains.kotlin.psi.KtLambdaExpression) {
                    val wrapper = findKnownSelectionWrapper(node, relativeFile, enclosingComposable)
                    if (wrapper != null) {
                        containers += wrapper
                        lambdaOrigin = true
                        walkState.lambdaOrigin = true
                    }
                }
                node = node.parent
            } else {
                val callName = extractCalleeName(callExpr)
                if (callName == null) {
                    node = node.parent
                } else {
                    applyAncestorCallName(
                        callName = callName,
                        callExpr = callExpr,
                        relativeFile = relativeFile,
                        enclosingComposable = enclosingComposable,
                        scAliases = scAliases,
                        dsAliases = dsAliases,
                        containers = containers,
                        state = walkState,
                    )
                    excluded = walkState.excluded
                    disableSelection = walkState.disableSelection
                    lambdaOrigin = walkState.lambdaOrigin
                    node = node.parent
                }
            }
        }

        val confidence =
            when {
                lambdaOrigin && containers.isNotEmpty() -> "caller-chain"
                else -> "lexical"
            }

        return SelectionContext(
            callee = callee ?: "<unknown>",
            inSelectionContainer = containers.isNotEmpty(),
            selectionContainerCount = containers.size,
            excludedByDisableSelection = excluded,
            selectionContainers = containers,
            disableSelection = disableSelection,
            confidence = confidence,
        )
    }

    private data class AncestorWalkState(
        var excluded: Boolean,
        var disableSelection: DisableSelectionInfo?,
        var lambdaOrigin: Boolean,
    )

    private fun applyAncestorCallName(
        callName: String,
        callExpr: KtCallExpression,
        relativeFile: String,
        enclosingComposable: KtNamedFunction,
        scAliases: Set<String>,
        dsAliases: Set<String>,
        containers: MutableList<SelectionContainerInfo>,
        state: AncestorWalkState,
    ) {
        when {
            callName in dsAliases -> {
                if (containers.isEmpty() && state.disableSelection == null) {
                    state.excluded = true
                    state.disableSelection =
                        DisableSelectionInfo(
                            file = relativeFile,
                            line = callExpr.getLineNumber(),
                            function = enclosingComposable.name ?: "<anonymous>",
                        )
                }
            }
            callName in scAliases || matchesKnownWrapper(callExpr, callName) -> {
                containers += selectionContainerInfo(relativeFile, callExpr, enclosingComposable)
            }
        }
    }

    private fun selectionContainerInfo(
        relativeFile: String,
        callExpr: KtCallExpression,
        enclosingComposable: KtNamedFunction,
    ): SelectionContainerInfo =
        SelectionContainerInfo(
            file = relativeFile,
            line = callExpr.getLineNumber(),
            function = enclosingComposable.name ?: "<anonymous>",
        )

    private fun matchesKnownWrapper(call: KtCallExpression, callName: String): Boolean {
        val rule = knownWrapperRules.firstOrNull { it.callee == callName } ?: return false
        return call.valueArguments.any { arg ->
            arg.getArgumentName()?.text == rule.providesSelectionWhenNamedArgument &&
                arg.getArgumentExpression()?.text == rule.providesSelectionWhenValue
        }
    }

    private fun findKnownSelectionWrapper(
        lambda: org.jetbrains.kotlin.psi.KtLambdaExpression,
        relativeFile: String,
        enclosingComposable: KtNamedFunction,
    ): SelectionContainerInfo? {
        val parentCall = lambda.parent.parent as? KtCallExpression ?: return null
        val callName = extractCalleeName(parentCall) ?: return null
        if (!matchesKnownWrapper(parentCall, callName)) {
            return null
        }
        return SelectionContainerInfo(
            file = relativeFile,
            line = parentCall.getLineNumber(),
            function = enclosingComposable.name ?: "<anonymous>",
        )
    }

    fun findCallAtLine(file: KtFile, relativeFile: String, line: Int): SelectionContext {
        val calls =
            file.collectDescendantsOfType<KtCallExpression>().filter { it.getLineNumber() == line }
        val target =
            calls.firstOrNull { call ->
                val name = extractCalleeName(call)
                name != null &&
                    name !in selectionContainerNames &&
                    name !in disableSelectionNames &&
                    !isSelectionContainerAlias(file, name) &&
                    !isDisableSelectionAlias(file, name)
            } ?: calls.firstOrNull() ?: error("No call expression at line $line in $relativeFile")
        return analyzeCallSite(target, relativeFile)
    }

    private fun findEnclosingComposable(
        call: KtCallExpression,
        relativeFile: String,
    ): KtNamedFunction {
        var current: PsiElement? = call.parent
        while (current != null) {
            if (current is KtNamedFunction && hasComposableAnnotation(current)) {
                return current
            }
            current = current.parent
        }
        error("Call site must be inside a @$composableAnnotation function: $relativeFile")
    }

    private fun hasComposableAnnotation(function: KtNamedFunction): Boolean =
        function.annotationEntries.any { entry ->
            entry.shortName?.asString() == composableAnnotation
        }

    private fun isSelectionContainerAlias(file: KtFile, name: String): Boolean =
        name in resolveAliases(file, selectionContainerNames)

    private fun isDisableSelectionAlias(file: KtFile, name: String): Boolean =
        name in resolveAliases(file, disableSelectionNames)

    private fun resolveAliases(file: KtFile, canonicalNames: Set<String>): Set<String> {
        val names = canonicalNames.toMutableSet()
        file.importDirectives.forEach { import ->
            val importedName = import.importedFqName?.shortName()?.asString() ?: return@forEach
            if (importedName in canonicalNames) {
                import.aliasName?.let { names += it }
            }
        }
        return names
    }

    private fun extractCalleeName(call: KtCallExpression): String? {
        val callee = call.calleeExpression ?: return null
        return when (callee) {
            is KtSimpleNameExpression -> callee.getReferencedName()
            is KtDotQualifiedExpression -> {
                (callee.selectorExpression as? KtSimpleNameExpression)?.getReferencedName()
            }
            else -> null
        }
    }

    private fun KtCallExpression.getLineNumber(): Int {
        val doc =
            containingFile.viewProvider.document ?: error("No document for ${containingFile.name}")
        return doc.getLineNumber(textRange.startOffset) + 1
    }

    companion object {
        const val COMPOSABLE_ANNOTATION = "Composable"
        val DEFAULT_SELECTION_CONTAINER_NAMES: Set<String> = setOf("SelectionContainer")
        val DEFAULT_DISABLE_SELECTION_NAMES: Set<String> = setOf("DisableSelection")
    }
}

private inline fun <reified T> org.jetbrains.kotlin.psi.KtElement.collectDescendantsOfType():
    List<T> {
    val results = mutableListOf<T>()
    accept(
        object : org.jetbrains.kotlin.com.intellij.psi.PsiElementVisitor() {
            override fun visitElement(element: org.jetbrains.kotlin.com.intellij.psi.PsiElement) {
                if (element is T) {
                    results += element
                }
                element.acceptChildren(this)
            }
        }
    )
    return results
}
