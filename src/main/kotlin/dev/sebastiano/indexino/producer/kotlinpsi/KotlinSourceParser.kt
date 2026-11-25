package dev.sebastiano.indexino.producer.kotlinpsi

import dev.sebastiano.indexino.core.record.CallArgumentRecord
import dev.sebastiano.indexino.core.record.ReferenceRecord
import dev.sebastiano.indexino.core.record.ResourceUsageRecord
import dev.sebastiano.indexino.core.record.SymbolRecord
import dev.sebastiano.indexino.parse.KotlinPsiParserPool
import dev.sebastiano.indexino.producer.IndexedSource
import dev.sebastiano.indexino.producer.xml.ResourceMetadata
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtCallableDeclaration
import org.jetbrains.kotlin.psi.KtCatchClause
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassBody
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtForExpression
import org.jetbrains.kotlin.psi.KtFunctionLiteral
import org.jetbrains.kotlin.psi.KtImportDirective
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtSimpleNameExpression
import org.jetbrains.kotlin.psi.KtSuperExpression
import org.jetbrains.kotlin.psi.KtSuperTypeCallEntry
import org.jetbrains.kotlin.psi.KtThisExpression
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid
import org.jetbrains.kotlin.psi.KtUnaryExpression
import org.jetbrains.kotlin.psi.psiUtil.startOffsetSkippingComments

internal class KotlinParseInput(
    val source: IndexedSource,
    val defaultResourcePackage: String?,
    val readSource: () -> String,
)

internal class KotlinParsedFile(
    val source: IndexedSource,
    internal val facts: KotlinFileFacts,
    val workerThreadName: String,
)

/** PSI-detached records for one Kotlin file. The syntax tree is not retained. */
internal class KotlinFileFacts(
    val symbols: List<SymbolRecord>,
    val calls: List<KotlinCallDraft>,
    val memberReferences: List<ReferenceRecord>,
    val qualifiedResourceUsages: List<ResourceUsageRecord>,
    val staticResourceUsages: List<ResourceUsageRecord>,
)

internal class KotlinCallArgumentDraft(
    val position: Int,
    val explicitName: String?,
    val nameFromLastParameter: Boolean,
    val kind: String,
    val startLine: Int,
    val startColumn: Int,
    val startOffset: Int,
    val endLine: Int,
    val endColumn: Int,
    val endOffset: Int,
    val nestedCallIdentities: List<String>,
) {
    fun resolve(parameterNames: List<String>): CallArgumentRecord =
        CallArgumentRecord(
            position = position,
            resolvedName =
                explicitName
                    ?: if (nameFromLastParameter) parameterNames.lastOrNull()
                    else parameterNames.getOrNull(position),
            kind = kind,
            startLine = startLine,
            startColumn = startColumn,
            startOffset = startOffset,
            endLine = endLine,
            endColumn = endColumn,
            endOffset = endOffset,
            nestedCallIdentities = nestedCallIdentities,
        )
}

internal class KotlinCallDraft(
    val identity: String,
    val calleeName: String,
    val target: KotlinCallResolution?,
    val valueArgumentCount: Int,
    val parameterLookupCount: Int,
    val enclosingFunctionLine: Int?,
    val enclosingFunctionName: String?,
    val parentCallIdentity: String?,
    val startLine: Int,
    val startColumn: Int,
    val startOffset: Int,
    val endLine: Int,
    val endColumn: Int,
    val endOffset: Int,
    val arguments: List<KotlinCallArgumentDraft>,
)

internal class KotlinInvocationTarget(
    val symbolFqn: String,
    val name: String,
    val qualifier: String?,
    val candidates: List<String> = listOf(symbolFqn),
)

/**
 * Cross-file lookup for a call without a receiver. Member resolution needs the run-local symbol
 * table; the remaining steps fall back to symbols stored by files this run did not process.
 */
internal class KotlinNamedCallResolution(
    val name: String,
    val classOwner: String?,
    val inheritedTarget: String?,
    val topLevelTarget: String,
    val imported: String?,
)

internal sealed interface KotlinCallResolution {
    class Resolved(val target: KotlinInvocationTarget) : KotlinCallResolution

    class Named(val resolution: KotlinNamedCallResolution) : KotlinCallResolution
}

/**
 * Parses Kotlin sources concurrently with worker-local PSI factories from [KotlinPsiParserPool].
 * Every source is read and walked exactly once; the resulting [KotlinFileFacts] never reference the
 * syntax tree.
 */
internal class KotlinSourceParser {
    fun parse(
        inputs: List<KotlinParseInput>,
        parsers: KotlinPsiParserPool,
        onParsed: ((KotlinParseInput, String, KtFile) -> Unit)? = null,
    ): List<KotlinParsedFile> {
        if (inputs.isEmpty()) return emptyList()

        val results = arrayOfNulls<KotlinParsedFile>(inputs.size)
        val failures = arrayOfNulls<Throwable>(inputs.size)
        val nextFile = AtomicInteger()
        val stopped = AtomicBoolean()
        val workerCount = parsers.workerCount.coerceAtMost(inputs.size)
        val threadIds = AtomicInteger()
        val executor =
            Executors.newFixedThreadPool(workerCount) { runnable ->
                Thread(runnable, "indexino-kotlin-parse-${threadIds.incrementAndGet()}").apply {
                    isDaemon = true
                }
            }
        val completed = ExecutorCompletionService<Unit>(executor)
        try {
            repeat(workerCount) { workerIndex ->
                completed.submit(
                    Callable {
                        runWorker(
                            parsers,
                            workerIndex,
                            inputs,
                            results,
                            failures,
                            nextFile,
                            stopped,
                            onParsed,
                        )
                    }
                )
            }
            repeat(workerCount) { completed.take().get() }
        } catch (failure: InterruptedException) {
            stopped.set(true)
            Thread.currentThread().interrupt()
            throw failure
        } catch (failure: ExecutionException) {
            stopped.set(true)
            throw failure.cause ?: failure
        } finally {
            if (stopped.get()) executor.shutdownNow() else executor.shutdown()
            executor.close()
        }

        failures.firstOrNull { failure -> failure != null }?.let { failure -> throw failure }
        return results.map { file -> checkNotNull(file) { "Kotlin file was not parsed" } }
    }

    private fun runWorker(
        parsers: KotlinPsiParserPool,
        worker: Int,
        inputs: List<KotlinParseInput>,
        results: Array<KotlinParsedFile?>,
        failures: Array<Throwable?>,
        nextFile: AtomicInteger,
        stopped: AtomicBoolean,
        onParsed: ((KotlinParseInput, String, KtFile) -> Unit)?,
    ) {
        while (true) {
            if (Thread.currentThread().isInterrupted) {
                throw InterruptedException("Kotlin parsing interrupted")
            }
            if (stopped.get()) return
            val index = nextFile.getAndIncrement()
            if (index >= inputs.size) return
            try {
                val input = inputs[index]
                val sourceText = input.readSource()
                // The PSI parses \n-only text: annotations detach when carriage reaches it and the
                // documents behind line lookups reject \r. Records carry original-file offsets.
                val normalized = NormalizedSourceText(sourceText)
                val file = parsers.parseFile(worker, input.source.path, normalized.text)
                val facts =
                    KotlinFileScanner(
                            originId = input.source.originId,
                            relativePath = input.source.path,
                            defaultResourcePackage = input.defaultResourcePackage,
                            file = file,
                            offsets = normalized,
                        )
                        .scan()
                onParsed?.invoke(input, sourceText, file)
                results[index] = KotlinParsedFile(input.source, facts, Thread.currentThread().name)
            } catch (@Suppress("TooGenericExceptionCaught") failure: Throwable) {
                failures[index] = failure
                stopped.set(true)
                return
            }
        }
    }

    companion object {
        fun workerCount(fileCount: Int): Int =
            defaultKotlinParseWorkers().coerceAtMost(fileCount).coerceAtLeast(1)
    }
}

internal fun defaultKotlinParseWorkers(): Int =
    Runtime.getRuntime().availableProcessors().coerceAtLeast(1)

private const val BOOLEAN_PREFIX_LENGTH = 2

@Suppress("TooManyFunctions", "LargeClass")
private class KotlinFileScanner(
    private val originId: String,
    private val relativePath: String,
    private val defaultResourcePackage: String?,
    private val file: KtFile,
    private val offsets: NormalizedSourceText,
) : KtTreeVisitorVoid() {
    private val imports: Map<String, String> =
        file.importDirectives
            .mapNotNull { directive ->
                val path = directive.importPath?.pathStr ?: return@mapNotNull null
                if (path.endsWith(".*")) {
                    null
                } else {
                    (directive.aliasName ?: path.substringAfterLast('.')) to path
                }
            }
            .toMap()
    private val names = KotlinSourceNames(file, imports, offsets)
    private val classSymbols = mutableListOf<SymbolRecord>()
    private val constructorPropertySymbols = mutableListOf<SymbolRecord>()
    private val functionSymbols = mutableListOf<SymbolRecord>()
    private val propertySymbols = mutableListOf<SymbolRecord>()
    private val calls = mutableListOf<KotlinCallDraft>()
    private val memberReferences = mutableListOf<ReferenceRecord>()
    private val qualifiedResourceUsages = mutableListOf<ResourceUsageRecord>()
    private val staticResourceUsages = mutableListOf<ResourceUsageRecord>()
    private val enclosingFunctions = ArrayDeque<KtNamedFunction>()
    private val parentCalls = ArrayDeque<KtCallExpression>()

    fun scan(): KotlinFileFacts {
        file.accept(this)
        return KotlinFileFacts(
            symbols =
                buildList {
                    addAll(classSymbols)
                    addAll(functionSymbols)
                    addAll(propertySymbols)
                    addAll(constructorPropertySymbols)
                },
            calls = calls.toList(),
            memberReferences = memberReferences.toList(),
            qualifiedResourceUsages = qualifiedResourceUsages.toList(),
            staticResourceUsages = staticResourceUsages.toList(),
        )
    }

    override fun visitClass(declaration: KtClass) {
        collectClassSymbol(declaration)
        collectConstructorPropertySymbols(declaration)
        super.visitClass(declaration)
    }

    override fun visitNamedFunction(function: KtNamedFunction) {
        collectFunctionSymbol(function)
        enclosingFunctions.addLast(function)
        try {
            super.visitNamedFunction(function)
        } finally {
            enclosingFunctions.removeLast()
        }
    }

    override fun visitProperty(property: KtProperty) {
        collectPropertySymbol(property)
        super.visitProperty(property)
    }

    override fun visitCallExpression(expression: KtCallExpression) {
        indexCall(expression)
        parentCalls.addLast(expression)
        try {
            super.visitCallExpression(expression)
        } finally {
            parentCalls.removeLast()
        }
    }

    override fun visitQualifiedExpression(expression: KtQualifiedExpression) {
        indexMemberReference(expression)
        indexResourceUsage(expression)
        super.visitQualifiedExpression(expression)
    }

    override fun visitSimpleNameExpression(expression: KtSimpleNameExpression) {
        if (expression is KtNameReferenceExpression) {
            indexStaticResourceUsage(expression)
        }
        super.visitSimpleNameExpression(expression)
    }

    private fun collectClassSymbol(declaration: KtClass) {
        val name = declaration.name ?: return
        val owner = names.classOwner(declaration)?.let(names::classFqn)
        classSymbols +=
            symbolRecord(
                fqn = owner?.let { "$it.$name" } ?: names.qualify(name),
                name = name,
                line = declaration.lineNumber(),
                column = declaration.columnNumber(),
                kind = if (declaration.isInterface()) "interface" else "class",
                ownerFqn = owner,
            )
    }

    private fun collectFunctionSymbol(function: KtNamedFunction) {
        if (function.parent !is KtFile && function.parent !is KtClassBody) return
        val name = function.name ?: return
        val owner = names.classOwner(function)?.let(names::classFqn)
        val fqn = owner?.let { "$it#$name" } ?: names.qualify(name)
        functionSymbols +=
            symbolRecord(
                fqn = fqn,
                name = name,
                line = function.lineNumber(),
                column = function.columnNumber(),
                kind = "function",
                ownerFqn = owner,
                signature =
                    function.valueParameters.joinToString(prefix = "(", postfix = ")") {
                        it.typeReference?.text.orEmpty()
                    },
                arity = function.valueParameters.size,
                parameterNames = function.valueParameters.mapNotNull { it.name },
                isVararg =
                    function.valueParameters.lastOrNull()?.hasModifier(KtTokens.VARARG_KEYWORD) ==
                        true,
                aliases =
                    if (owner == null) {
                        listOf(
                            "${names.fileFacadeFqn()}#${names.functionJvmName(function) ?: name}"
                        )
                    } else {
                        emptyList()
                    },
            )
    }

    private fun collectPropertySymbol(property: KtProperty) {
        if (property.parent !is KtFile && property.parent !is KtClassBody) return
        val name = property.name ?: return
        val owner = names.classOwner(property)?.let(names::classFqn)
        propertySymbols +=
            symbolRecord(
                fqn = owner?.let { "$it#$name" } ?: names.qualify(name),
                name = name,
                line = property.lineNumber(),
                column = property.columnNumber(),
                kind = "property",
                ownerFqn = owner,
                signature = property.typeReference?.text,
                aliases = names.propertyAliases(owner, property),
            )
    }

    private fun collectConstructorPropertySymbols(declaration: KtClass) {
        val owner = names.classFqn(declaration)
        for (parameter in declaration.primaryConstructorParameters.filter { it.hasValOrVar() }) {
            val name = parameter.name ?: continue
            constructorPropertySymbols +=
                symbolRecord(
                    fqn = "$owner#$name",
                    name = name,
                    line = parameter.lineNumber(),
                    column = parameter.columnNumber(),
                    kind = "property",
                    ownerFqn = owner,
                    signature = parameter.typeReference?.text,
                    aliases =
                        names.propertyAliases(
                            owner = owner,
                            name = name,
                            isVar = parameter.valOrVarKeyword?.text == "var",
                            declaredType = parameter.typeReference?.text,
                        ),
                )
        }
    }

    private fun symbolRecord(
        fqn: String,
        name: String,
        line: Int,
        column: Int,
        kind: String,
        ownerFqn: String? = null,
        signature: String? = null,
        arity: Int? = null,
        parameterNames: List<String> = emptyList(),
        isVararg: Boolean = false,
        aliases: List<String> = emptyList(),
    ): SymbolRecord =
        SymbolRecord(
            fqn = fqn,
            relativeFile = relativePath,
            line = line,
            column = column,
            kind = kind,
            name = name,
            language = LANGUAGE,
            ownerFqn = ownerFqn,
            signature = signature,
            arity = arity,
            parameterNames = parameterNames,
            isVararg = isVararg,
            aliases = aliases,
            originId = originId,
        )

    private fun indexCall(call: KtCallExpression) {
        val name = (call.calleeExpression as? KtSimpleNameExpression)?.getReferencedName()
        val resolution = name?.let { resolveCall(call, it) }
        val line = call.lineNumber()
        val column = call.columnNumber()
        val enclosing = enclosingFunctions.lastOrNull()
        calls +=
            KotlinCallDraft(
                identity = callIdentity(call),
                calleeName = call.calleeExpression?.text ?: "<unknown>",
                target = resolution,
                valueArgumentCount = call.valueArguments.size,
                parameterLookupCount = call.valueArguments.size + call.lambdaArguments.size,
                enclosingFunctionLine = enclosing?.lineNumber(),
                enclosingFunctionName = enclosing?.name,
                parentCallIdentity = parentCalls.lastOrNull()?.let(::callIdentity),
                startLine = line,
                startColumn = column,
                startOffset = offsets.toOriginalOffset(call.textRange.startOffset),
                endLine = call.endLineNumber(),
                endColumn = call.endColumnNumber(),
                endOffset = call.inclusiveEndOffset(),
                arguments = callArguments(call),
            )
    }

    private fun resolveCall(call: KtCallExpression, name: String): KotlinCallResolution? {
        val qualifiedParent = call.parent as? KtQualifiedExpression
        val receiver = qualifiedParent?.takeIf { it.selectorExpression == call }?.receiverExpression
        if (receiver != null) {
            val owner = resolveReceiverOwner(call, receiver) ?: return null
            return KotlinCallResolution.Resolved(
                KotlinInvocationTarget("$owner#$name", name, receiver.text)
            )
        }
        val imported = imports[name]
        return KotlinCallResolution.Named(
            KotlinNamedCallResolution(
                name = name,
                classOwner = names.classOwner(call)?.let(names::classFqn),
                inheritedTarget = names.superClassFqn(call)?.let { "$it#$name" },
                topLevelTarget = names.qualify(name),
                imported = imported,
            )
        )
    }

    private fun resolveReceiverOwner(call: KtCallExpression, receiver: KtExpression): String? =
        when (receiver) {
            is KtThisExpression -> names.classOwner(call)?.let(names::classFqn)
            is KtSuperExpression ->
                receiver.superTypeQualifier?.text?.let { names.qualifyType(it, call) }
                    ?: names.superClassFqn(call)
            is KtNameReferenceExpression ->
                resolveNamedReceiverOwner(receiver, receiver.getReferencedName())
            is KtCallExpression ->
                (receiver.calleeExpression as? KtSimpleNameExpression)
                    ?.getReferencedName()
                    ?.let { names.resolveCallReceiverType(receiver, it) }
                    ?.let { names.qualifyType(it, call) }
            is KtDotQualifiedExpression -> names.resolveQualifiedReceiverOwner(call, receiver)
            else -> names.qualifyType(receiver.text, call)
        }

    private fun resolveNamedReceiverOwner(useSite: KtElement, name: String): String? {
        var scope = useSite.parent
        var insideMemberFunction = false
        while (scope != null) {
            if (scope is KtNamedFunction && scope.parent is KtClassBody) {
                insideMemberFunction = true
            }
            val binding = variableInScope(scope, useSite, name, insideMemberFunction)
            if (binding != null) {
                // An unknown type still shadows outer variables, imports, and objects.
                return binding.typeReference?.text?.let { names.qualifyType(it, binding) }
                    ?: localInitializerType(binding)
            }
            scope = scope.parent
        }
        return names.resolveTypeOrObject(useSite, name)
    }

    private fun localInitializerType(binding: KtCallableDeclaration): String? {
        val property = (binding as? KtProperty)?.takeIf { it.isLocal } ?: return null
        val call = property.initializer as? KtCallExpression ?: return null
        val name =
            (call.calleeExpression as? KtSimpleNameExpression)?.getReferencedName() ?: return null
        var scope = call.parent
        var insideMemberFunction = false
        while (scope != null) {
            if (scope is KtNamedFunction && scope.parent is KtClassBody) {
                insideMemberFunction = true
            }
            if (variableInScope(scope, call, name, insideMemberFunction) != null) return null
            scope = scope.parent
        }
        // Use the declaration's scope, not a later use site's potentially shadowed type names.
        return names.resolveCallReceiverType(call, name)?.let { names.qualifyType(it, call) }
    }

    private fun variableInScope(
        scope: PsiElement,
        useSite: KtElement,
        name: String,
        insideMemberFunction: Boolean,
    ): KtCallableDeclaration? =
        when (scope) {
            is KtNamedFunction -> scope.valueParameters.firstOrNull { it.name == name }
            is KtFunctionLiteral -> scope.valueParameters.firstOrNull { it.name == name }
            is KtCatchClause -> scope.catchParameter?.takeIf { it.name == name }
            is KtForExpression -> scope.loopParameter?.takeIf { it.name == name }
            is KtBlockExpression ->
                scope.statements.filterIsInstance<KtProperty>().lastOrNull {
                    it.name == name && it.textOffset < useSite.textOffset
                }
            is KtClass ->
                scope.declarations.filterIsInstance<KtProperty>().firstOrNull { it.name == name }
                    ?: scope.primaryConstructorParameters.firstOrNull {
                        it.name == name && (it.hasValOrVar() || !insideMemberFunction)
                    }
            is KtClassOrObject ->
                scope.declarations.filterIsInstance<KtProperty>().firstOrNull { it.name == name }
            is KtFile ->
                scope.declarations.filterIsInstance<KtProperty>().firstOrNull { it.name == name }
            else -> null
        }

    private fun indexMemberReference(expression: KtQualifiedExpression) {
        val selector = expression.selectorExpression as? KtNameReferenceExpression ?: return
        val receiver = expression.receiverExpression
        val name = selector.getReferencedName()
        val owner = resolveMemberReceiverOwner(receiver, expression) ?: return
        val target = "$owner#$name"
        val capitalized = name.replaceFirstChar { it.uppercaseChar() }
        val isBooleanStyleName =
            name.startsWith("is") &&
                name.length > BOOLEAN_PREFIX_LENGTH &&
                name[BOOLEAN_PREFIX_LENGTH].isUpperCase()
        val booleanGetter =
            if (isBooleanStyleName) {
                "$owner#$name"
            } else {
                "$owner#is$capitalized"
            }
        val setterName = if (isBooleanStyleName) name.removePrefix("is") else capitalized
        val isWrite = expression.isWriteAccess()
        val candidates =
            buildList {
                    add(target)
                    add("$owner#get$capitalized")
                    add(booleanGetter)
                    if (isWrite) {
                        add("$owner#set$setterName")
                    }
                }
                .distinct()
        val line = selector.lineNumber()
        val column = selector.columnNumber()
        memberReferences +=
            ReferenceRecord(
                symbolFqn = target,
                relativeFile = relativePath,
                originId = originId,
                line = line,
                column = column,
                context = if (isWrite) "member-write" else "member",
                language = LANGUAGE,
                referencedName = name,
                qualifier = receiver.text,
                candidateSymbolFqns = candidates,
            )
    }

    private fun resolveMemberReceiverOwner(receiver: KtExpression, useSite: KtElement): String? =
        when (receiver) {
            is KtThisExpression -> names.classOwner(useSite)?.let(names::classFqn)
            is KtSuperExpression -> names.superClassFqn(useSite)
            is KtNameReferenceExpression ->
                resolveNamedReceiverOwner(receiver, receiver.getReferencedName())
            else -> null
        }

    private fun indexResourceUsage(expression: KtQualifiedExpression) {
        if (generateSequence(expression.parent) { it.parent }.any { it is KtImportDirective }) {
            return
        }
        val parent = expression.parent
        if (parent is KtQualifiedExpression && parent.receiverExpression == expression) {
            return
        }
        val chain = qualifiedNameChain(expression) ?: return
        val accessor = resourceAccessor(chain.segments) ?: return
        val rIndex = accessor.index
        val explicitPackage = chain.segments.take(rIndex).joinToString(".").ifBlank { null }
        val importedOwner = imports[chain.segments[rIndex]]
        val packageName =
            explicitPackage
                ?: importedOwner?.substringBeforeLast('.', "")?.takeIf(String::isNotBlank)
                ?: defaultResourcePackage
                ?: file.packageFqName.asString().ifBlank { null }
        val type = chain.segments[rIndex + 1]
        val name = chain.segments[rIndex + 2]
        val selector = chain.selectors.getOrNull(rIndex + 1) ?: return
        qualifiedResourceUsages +=
            ResourceUsageRecord(
                packageName = packageName,
                type = type,
                name = name,
                relativeFile = relativePath,
                line = selector.lineNumber(),
                column = selector.columnNumber(),
                offset = offsets.toOriginalOffset(selector.textOffset),
                language = LANGUAGE,
                originId = originId,
            )
    }

    private fun indexStaticResourceUsage(expression: KtNameReferenceExpression) {
        if (generateSequence(expression.parent) { it.parent }.any { it is KtImportDirective }) {
            return
        }
        val parent = expression.parent
        if (parent is KtQualifiedExpression && parent.selectorExpression == expression) {
            return
        }
        val name = expression.getReferencedName()
        val imported = imports[name] ?: return
        val marker = ".R."
        if (marker !in imported) return
        val resourcePackage = imported.substringBefore(marker)
        val parts = imported.substringAfter(marker).split('.')
        if (parts.size != 2 || parts[0] !in ResourceMetadata.RESOURCE_TYPES) {
            return
        }
        val resourceName = parts[1]
        if (hasShadowedName(expression, name)) return
        staticResourceUsages +=
            ResourceUsageRecord(
                packageName = resourcePackage,
                type = parts[0],
                name = resourceName,
                relativeFile = relativePath,
                line = expression.lineNumber(),
                column = expression.columnNumber(),
                offset = offsets.toOriginalOffset(expression.textOffset),
                language = LANGUAGE,
                originId = originId,
            )
    }

    private fun hasShadowedName(useSite: KtElement, name: String): Boolean {
        var scope = useSite.parent
        var insideMemberFunction = false
        while (scope != null) {
            if (scope is KtNamedFunction && scope.parent is KtClassBody) {
                insideMemberFunction = true
            }
            if (hasNameInScope(scope, useSite, name, insideMemberFunction)) return true
            scope = scope.parent
        }
        return false
    }

    private fun hasNameInScope(
        scope: PsiElement,
        useSite: KtElement,
        name: String,
        insideMemberFunction: Boolean,
    ): Boolean =
        when (scope) {
            is KtNamedFunction ->
                (insideMemberFunction || scope.parent !is KtClassBody) &&
                    scope.valueParameters.any { it.name == name }
            is KtFunctionLiteral -> scope.valueParameters.any { it.name == name }
            is KtCatchClause -> scope.catchParameter?.name == name
            is KtForExpression -> scope.loopParameter?.name == name
            is KtBlockExpression ->
                scope.statements.filterIsInstance<KtProperty>().any {
                    it.name == name && it.textOffset < useSite.textOffset
                }
            is KtClassOrObject ->
                scope.declarations.filterIsInstance<KtProperty>().any { it.name == name } ||
                    (scope as? KtClass)?.primaryConstructorParameters.orEmpty().any {
                        it.hasValOrVar() && it.name == name
                    }
            is KtFile -> scope.declarations.filterIsInstance<KtProperty>().any { it.name == name }
            else -> false
        }

    private fun resourceAccessor(segments: List<String>): ResourceAccessor? =
        segments.indices.firstNotNullOfOrNull { index ->
            if (index + 2 > segments.lastIndex) return@firstNotNullOfOrNull null
            val importedSimpleName = imports[segments[index]]?.substringAfterLast('.')
            when {
                segments[index] == "Res" || importedSimpleName == "Res" ->
                    ResourceAccessor(index, compose = true)
                segments[index] == "R" || importedSimpleName == "R" ->
                    ResourceAccessor(index, compose = false)
                else -> null
            }?.takeIf { segments[index + 1] in ResourceMetadata.RESOURCE_TYPES }
        }

    private fun qualifiedNameChain(expression: KtExpression): QualifiedNameChain? =
        when (expression) {
            is KtNameReferenceExpression ->
                QualifiedNameChain(listOf(expression.getReferencedName()), emptyList())
            is KtQualifiedExpression -> {
                val receiver = qualifiedNameChain(expression.receiverExpression) ?: return null
                val selectorExpression = expression.selectorExpression ?: return null
                val selectorName =
                    when (selectorExpression) {
                        is KtNameReferenceExpression -> selectorExpression.getReferencedName()
                        is KtCallExpression ->
                            (selectorExpression.calleeExpression as? KtNameReferenceExpression)
                                ?.getReferencedName() ?: return null
                        else -> return null
                    }
                QualifiedNameChain(
                    receiver.segments + selectorName,
                    receiver.selectors + selectorExpression,
                )
            }
            else -> null
        }

    private fun callArguments(call: KtCallExpression): List<KotlinCallArgumentDraft> = buildList {
        call.valueArguments.forEachIndexed { position, argument ->
            val expression = argument.getArgumentExpression() ?: return@forEachIndexed
            add(
                expression.toArgumentDraft(
                    position = position,
                    explicitName = argument.getArgumentName()?.asName?.identifier,
                    nameFromLastParameter = false,
                    kind = if (expression is KtLambdaExpression) "LAMBDA" else "VALUE",
                )
            )
        }
        call.lambdaArguments
            .filter { lambda ->
                call.valueArguments.none { value -> value.textRange == lambda.textRange }
            }
            .forEachIndexed { lambdaIndex, argument ->
                val expression = argument.getLambdaExpression() ?: return@forEachIndexed
                add(
                    expression.toArgumentDraft(
                        position = call.valueArguments.size + lambdaIndex,
                        explicitName = null,
                        nameFromLastParameter = true,
                        kind = "TRAILING_LAMBDA",
                    )
                )
            }
    }

    private fun KtExpression.toArgumentDraft(
        position: Int,
        explicitName: String?,
        nameFromLastParameter: Boolean,
        kind: String,
    ): KotlinCallArgumentDraft =
        KotlinCallArgumentDraft(
            position = position,
            explicitName = explicitName,
            nameFromLastParameter = nameFromLastParameter,
            kind = kind,
            startLine = lineNumber(),
            startColumn = columnNumber(),
            startOffset = offsets.toOriginalOffset(textRange.startOffset),
            endLine = endLineNumber(),
            endColumn = endColumnNumber(),
            endOffset = inclusiveEndOffset(),
            nestedCallIdentities =
                collectDescendantsOfType<KtCallExpression>().map { call -> callIdentity(call) },
        )

    private fun callIdentity(call: KtCallExpression): String =
        "$originId:$relativePath:${offsets.toOriginalOffset(call.textRange.startOffset)}"

    /** Inclusive end offset in the original file's bytes. */
    private fun KtElement.inclusiveEndOffset(): Int =
        offsets.toOriginalOffset((textRange.endOffset - 1).coerceAtLeast(textRange.startOffset))

    private data class ResourceAccessor(val index: Int, val compose: Boolean)

    private data class QualifiedNameChain(
        val segments: List<String>,
        val selectors: List<KtExpression>,
    )

    private companion object {
        const val LANGUAGE = "kotlin"
    }
}

private class KotlinSourceNames(
    private val file: KtFile,
    private val imports: Map<String, String> = emptyMap(),
    private val offsets: NormalizedSourceText,
) {
    fun resolveCallReceiverType(useSite: KtElement, name: String): String? {
        var scope = useSite.parent
        while (scope != null) {
            val function =
                when (scope) {
                    is KtBlockExpression ->
                        scope.statements.filterIsInstance<KtNamedFunction>().lastOrNull {
                            it.name == name && it.textOffset < useSite.textOffset
                        }
                    is KtClassOrObject ->
                        scope.declarations.filterIsInstance<KtNamedFunction>().firstOrNull {
                            it.name == name
                        }
                    is KtFile ->
                        scope.declarations.filterIsInstance<KtNamedFunction>().firstOrNull {
                            it.name == name
                        }
                    else -> null
                }
            if (function != null) {
                return function.typeReference?.text
            }
            scope = scope.parent
        }
        return resolveTypeOrObject(useSite, name)
    }

    fun resolveTypeOrObject(useSite: KtElement, name: String): String? {
        var scope = useSite.parent
        while (scope != null) {
            val declaration =
                when (scope) {
                    is KtBlockExpression ->
                        scope.statements.filterIsInstance<KtClassOrObject>().lastOrNull {
                            it.name == name && it.textOffset < useSite.textOffset
                        }
                    is KtClassOrObject ->
                        scope.declarations.filterIsInstance<KtClassOrObject>().firstOrNull {
                            it.name == name
                        }
                    is KtFile ->
                        scope.declarations.filterIsInstance<KtClassOrObject>().firstOrNull {
                            it.name == name
                        }
                    else -> null
                }
            if (declaration != null) {
                return classFqn(declaration)
            }
            scope = scope.parent
        }
        return imports[name]
            ?: name.takeIf { it.firstOrNull()?.isUpperCase() == true }?.let(::qualify)
    }

    fun superClassFqn(useSite: KtElement): String? {
        val owner = classOwner(useSite) ?: return null
        val superType =
            (owner.superTypeListEntries.firstOrNull { it is KtSuperTypeCallEntry }
                    ?: owner.superTypeListEntries.firstOrNull())
                ?.typeReference
                ?.text ?: return null
        return qualifyType(superType, useSite)
    }

    fun resolveQualifiedReceiverOwner(
        useSite: KtElement,
        receiver: KtDotQualifiedExpression,
    ): String? {
        val selfReceiver = receiver.receiverExpression
        val field = receiver.selectorExpression as? KtNameReferenceExpression
        return when {
            selfReceiver is KtThisExpression && field != null ->
                resolveClassPropertyType(field, field.getReferencedName())?.let {
                    qualifyType(it, useSite)
                }
            selfReceiver is KtSuperExpression -> null
            else -> qualifyType(receiver.text, useSite)
        }
    }

    private fun resolveClassPropertyType(useSite: KtElement, name: String): String? {
        var scope = useSite.parent
        while (scope != null) {
            if (scope is KtClassOrObject) {
                val constructorType =
                    (scope as? KtClass)
                        ?.primaryConstructorParameters
                        ?.firstOrNull { it.hasValOrVar() && it.name == name }
                        ?.typeReference
                        ?.text
                return constructorType
                    ?: scope.declarations
                        .filterIsInstance<KtProperty>()
                        .firstOrNull { it.name == name }
                        ?.typeReference
                        ?.text
            }
            scope = scope.parent
        }
        return null
    }

    fun qualifyType(raw: String, useSite: KtElement? = null): String {
        val type = raw.substringBefore('<').removeSuffix("?").trim()
        imports[type]?.let {
            return it
        }
        if ('.' in type) {
            return type
        }
        var owner = useSite?.let(::classOwner)
        while (owner != null) {
            owner.declarations
                .filterIsInstance<KtClassOrObject>()
                .firstOrNull { it.name == type }
                ?.let {
                    return classFqn(it)
                }
            owner = classOwner(owner)
        }
        return qualify(type)
    }

    fun classFqn(declaration: KtClassOrObject): String {
        val names = mutableListOf<String>()
        var current: KtClassOrObject? = declaration
        while (current != null) {
            names += current.name ?: "<anonymous@${offsets.toOriginalOffset(current.textOffset)}>"
            current = classOwner(current)
        }
        return qualify(names.asReversed().joinToString("."))
    }

    fun classOwner(element: KtElement): KtClassOrObject? {
        var current = element.parent
        while (current != null) {
            if (current is KtClassOrObject) {
                return current
            }
            current = current.parent
        }
        return null
    }

    fun qualify(name: String): String {
        val pkg = file.packageFqName.asString()
        return if (pkg.isBlank()) name else "$pkg.$name"
    }

    fun fileFacadeFqn(): String =
        qualify(
            file.fileAnnotationList
                ?.annotationEntries
                ?.firstOrNull { it.shortName?.asString() == "JvmName" }
                ?.valueArguments
                ?.singleOrNull()
                ?.getArgumentExpression()
                ?.text
                ?.removeSurrounding("\"")
                ?.takeIf { it.isNotBlank() }
                ?: (file.name
                    .substringAfterLast('/')
                    .substringAfterLast('\\')
                    .substringBeforeLast('.') + "Kt")
        )

    fun functionJvmName(function: KtNamedFunction): String? =
        function.annotationEntries
            .firstOrNull { it.shortName?.asString() == "JvmName" }
            ?.valueArguments
            ?.singleOrNull()
            ?.getArgumentExpression()
            ?.text
            ?.removeSurrounding("\"")
            ?.takeIf { it.isNotBlank() }

    fun propertyAliases(owner: String?, property: KtProperty): List<String> {
        val name = property.name ?: return emptyList()
        return propertyAliases(owner, name, property.isVar, property.typeReference?.text)
    }

    fun propertyAliases(
        owner: String?,
        name: String,
        isVar: Boolean,
        declaredType: String?,
    ): List<String> {
        val accessorOwner = owner ?: fileFacadeFqn()
        val capitalized = name.replaceFirstChar { it.uppercaseChar() }
        val normalizedType = declaredType?.removeSuffix("?")
        val isBooleanIsProperty =
            name.startsWith("is") &&
                name.length > IS_PREFIX_LENGTH &&
                name[IS_PREFIX_LENGTH].isUpperCase() &&
                (normalizedType == null || normalizedType in BOOLEAN_TYPE_NAMES)
        return buildList {
            if (isBooleanIsProperty) {
                add("$accessorOwner#$name")
            } else {
                add("$accessorOwner#get$capitalized")
            }
            if (isVar) {
                val setterName = if (isBooleanIsProperty) name.removePrefix("is") else capitalized
                add("$accessorOwner#set$setterName")
            }
        }
    }

    private companion object {
        const val IS_PREFIX_LENGTH = 2
        val BOOLEAN_TYPE_NAMES = setOf("Boolean", "kotlin.Boolean")
    }
}

private fun KtQualifiedExpression.isWriteAccess(): Boolean {
    val container = parent
    return when {
        container is KtBinaryExpression && container.left == this ->
            KtTokens.ALL_ASSIGNMENTS.contains(container.operationToken)
        container is KtUnaryExpression && container.baseExpression == this ->
            container.operationToken == KtTokens.PLUSPLUS ||
                container.operationToken == KtTokens.MINUSMINUS
        else -> false
    }
}

private fun KtElement.lineNumber(): Int {
    val document = containingFile.viewProvider.document ?: return 1
    return document.getLineNumber(startOffsetSkippingComments) + 1
}

private fun KtElement.columnNumber(): Int {
    val document = containingFile.viewProvider.document ?: return 1
    val offset = startOffsetSkippingComments
    val line = document.getLineNumber(offset)
    return offset - document.getLineStartOffset(line) + 1
}

private fun KtElement.endLineNumber(): Int {
    val document = containingFile.viewProvider.document ?: return lineNumber()
    return document.getLineNumber((textRange.endOffset - 1).coerceAtLeast(textRange.startOffset)) +
        1
}

private fun KtElement.endColumnNumber(): Int {
    val document = containingFile.viewProvider.document ?: return columnNumber()
    val offset = (textRange.endOffset - 1).coerceAtLeast(textRange.startOffset)
    val line = document.getLineNumber(offset)
    return offset - document.getLineStartOffset(line) + 1
}

private inline fun <reified T> KtElement.collectDescendantsOfType(): List<T> {
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
