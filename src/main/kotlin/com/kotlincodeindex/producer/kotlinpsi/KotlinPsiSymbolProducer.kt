package com.kotlincodeindex.producer.kotlinpsi

import com.kotlincodeindex.core.key.CodeIndexKey
import com.kotlincodeindex.core.record.ReferenceRecord
import com.kotlincodeindex.core.record.SymbolRecord
import com.kotlincodeindex.core.store.CodeIndexStore
import com.kotlincodeindex.parse.KotlinPsiParser
import com.kotlincodeindex.producer.IndexBuildContext
import com.kotlincodeindex.producer.IndexProducer
import com.kotlincodeindex.producer.SourceRecordCleanup
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtCallExpression
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
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtSimpleNameExpression
import org.jetbrains.kotlin.psi.KtSuperExpression
import org.jetbrains.kotlin.psi.KtSuperTypeCallEntry
import org.jetbrains.kotlin.psi.KtThisExpression
import org.jetbrains.kotlin.psi.KtUnaryExpression

class KotlinPsiSymbolProducer : IndexProducer {
    override val id: String = "kotlin-psi-symbols"
    override val namespace: String = "sym"
    override val displayName: String = "KotlinPsiSymbolProducer"

    override fun produce(context: IndexBuildContext, store: CodeIndexStore) {
        val affectedFiles =
            (context.changedSourceFiles + context.deletedSourceFiles).filterTo(linkedSetOf()) {
                it.endsWith(".kt")
            }
        SourceRecordCleanup.deleteLanguageRecords(store, LANGUAGE, ".kt", affectedFiles)
        KotlinPsiParser().use { parser ->
            val ktFiles =
                context.sourceFiles.filter {
                    it.endsWith(".kt") && it in context.changedSourceFiles
                }
            ktFiles.forEachIndexed { index, relativePath ->
                context.reportFileProgress(index + 1, ktFiles.size, relativePath)
                indexFile(
                    parser.parseFile(relativePath, context.readSource(relativePath)),
                    relativePath,
                    store,
                )
            }
        }
    }

    private fun indexFile(file: KtFile, relativePath: String, store: CodeIndexStore) {
        val symbols = collectSymbols(file)
        symbols.forEach { symbol ->
            store.put(
                CodeIndexKey.symbolDefinition(symbol.fqn, relativePath, symbol.line, symbol.column),
                SymbolRecord(
                    fqn = symbol.fqn,
                    relativeFile = relativePath,
                    line = symbol.line,
                    kind = symbol.kind,
                    name = symbol.name,
                    language = LANGUAGE,
                    ownerFqn = symbol.ownerFqn,
                    signature = symbol.signature,
                    arity = symbol.arity,
                    aliases = symbol.aliases,
                ),
            )
        }

        val imports =
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
        for (call in file.collectDescendantsOfType<KtCallExpression>()) {
            val target = resolveCall(file, call, symbols, imports) ?: continue
            val line = call.lineNumber()
            val column = call.columnNumber()
            store.put(
                CodeIndexKey.ref(target.symbolFqn, relativePath, line, column),
                ReferenceRecord(
                    symbolFqn = target.symbolFqn,
                    relativeFile = relativePath,
                    line = line,
                    column = column,
                    context = "call",
                    language = LANGUAGE,
                    referencedName = target.name,
                    qualifier = target.qualifier,
                    candidateSymbolFqns = target.candidates,
                    arity = call.valueArguments.size,
                ),
            )
        }
        indexMemberReferences(file, relativePath, store, imports)
    }

    private fun collectSymbols(file: KtFile): List<ResolvedSymbol> {
        val results = mutableListOf<ResolvedSymbol>()
        results += collectClassSymbols(file)
        results += collectFunctionSymbols(file)
        results += collectPropertySymbols(file)
        results += collectConstructorPropertySymbols(file)
        return results
    }

    private fun collectClassSymbols(file: KtFile): List<ResolvedSymbol> = buildList {
        val names = KotlinSourceNames(file)
        for (declaration in file.collectDescendantsOfType<KtClass>()) {
            val name = declaration.name ?: continue
            val owner = names.classOwner(declaration)?.let(names::classFqn)
            val fqn = owner?.let { "$it.$name" } ?: names.qualify(name)
            add(
                ResolvedSymbol(
                    fqn = fqn,
                    name = name,
                    line = declaration.lineNumber(),
                    column = declaration.columnNumber(),
                    kind = if (declaration.isInterface()) "interface" else "class",
                    ownerFqn = owner,
                )
            )
        }
    }

    private fun collectFunctionSymbols(file: KtFile): List<ResolvedSymbol> = buildList {
        val names = KotlinSourceNames(file)
        val declarations =
            file.collectDescendantsOfType<KtNamedFunction>().filter {
                it.parent is KtFile || it.parent is KtClassBody
            }
        for (function in declarations) {
            val name = function.name ?: continue
            val owner = names.classOwner(function)?.let(names::classFqn)
            val fqn = owner?.let { "$it#$name" } ?: names.qualify(name)
            val signature =
                function.valueParameters.joinToString(prefix = "(", postfix = ")") {
                    it.typeReference?.text.orEmpty()
                }
            add(
                ResolvedSymbol(
                    fqn = fqn,
                    name = name,
                    line = function.lineNumber(),
                    column = function.columnNumber(),
                    kind = "function",
                    ownerFqn = owner,
                    signature = signature,
                    arity = function.valueParameters.size,
                    aliases =
                        if (owner == null) {
                            listOf("${names.fileFacadeFqn()}#$name")
                        } else {
                            emptyList()
                        },
                )
            )
        }
    }

    private fun collectPropertySymbols(file: KtFile): List<ResolvedSymbol> = buildList {
        val names = KotlinSourceNames(file)
        val declarations =
            file.collectDescendantsOfType<KtProperty>().filter {
                it.parent is KtFile || it.parent is KtClassBody
            }
        for (property in declarations) {
            val name = property.name ?: continue
            val owner = names.classOwner(property)?.let(names::classFqn)
            val fqn = owner?.let { "$it#$name" } ?: names.qualify(name)
            add(
                ResolvedSymbol(
                    fqn = fqn,
                    name = name,
                    line = property.lineNumber(),
                    column = property.columnNumber(),
                    kind = "property",
                    ownerFqn = owner,
                    signature = property.typeReference?.text,
                    aliases = names.propertyAliases(owner, property),
                )
            )
        }
    }

    private fun collectConstructorPropertySymbols(file: KtFile): List<ResolvedSymbol> = buildList {
        val names = KotlinSourceNames(file)
        for (declaration in file.collectDescendantsOfType<KtClass>()) {
            val owner = names.classFqn(declaration)
            for (parameter in
                declaration.primaryConstructorParameters.filter { it.hasValOrVar() }) {
                val name = parameter.name ?: continue
                add(
                    ResolvedSymbol(
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
                )
            }
        }
    }

    private fun indexMemberReferences(
        file: KtFile,
        relativePath: String,
        store: CodeIndexStore,
        imports: Map<String, String>,
    ) {
        val names = KotlinSourceNames(file, imports)
        file.collectDescendantsOfType<KtQualifiedExpression>().forEach { expression ->
            val selector =
                expression.selectorExpression as? KtNameReferenceExpression ?: return@forEach
            val receiver = expression.receiverExpression
            val name = selector.getReferencedName()
            val owner = resolveMemberReceiverOwner(receiver, expression, names) ?: return@forEach
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
            store.put(
                CodeIndexKey.ref(target, relativePath, line, column),
                ReferenceRecord(
                    symbolFqn = target,
                    relativeFile = relativePath,
                    line = line,
                    column = column,
                    context = if (isWrite) "member-write" else "member",
                    language = LANGUAGE,
                    referencedName = name,
                    qualifier = receiver.text,
                    candidateSymbolFqns = candidates,
                ),
            )
        }
    }

    private fun resolveMemberReceiverOwner(
        receiver: KtExpression,
        useSite: KtElement,
        names: KotlinSourceNames,
    ): String? =
        when (receiver) {
            is KtThisExpression -> names.classOwner(useSite)?.let(names::classFqn)
            is KtSuperExpression -> names.superClassFqn(useSite)
            is KtNameReferenceExpression -> {
                val name = receiver.getReferencedName()
                val type =
                    resolveVariableType(receiver, name) ?: names.resolveTypeOrObject(receiver, name)
                type?.let { names.qualifyType(it, useSite) }
            }
            else -> null
        }

    private fun resolveCall(
        file: KtFile,
        call: KtCallExpression,
        symbols: List<ResolvedSymbol>,
        imports: Map<String, String>,
    ): InvocationTarget? {
        val names = KotlinSourceNames(file, imports)
        val name =
            (call.calleeExpression as? KtSimpleNameExpression)?.getReferencedName() ?: return null
        val qualifiedParent = call.parent as? KtQualifiedExpression
        val receiver = qualifiedParent?.takeIf { it.selectorExpression == call }?.receiverExpression
        if (receiver != null) {
            val owner = resolveReceiverOwner(call, receiver, names) ?: return null
            return InvocationTarget("$owner#$name", name, receiver.text)
        }
        val classOwner = names.classOwner(call)?.let(names::classFqn)
        symbols
            .firstOrNull { it.name == name && it.ownerFqn == classOwner }
            ?.let {
                return InvocationTarget(it.fqn, name, null)
            }
        symbols
            .firstOrNull { it.name == name && it.ownerFqn == null }
            ?.let {
                return InvocationTarget(it.fqn, name, null)
            }
        imports[name]?.let { imported ->
            val memberId =
                "${imported.substringBeforeLast('.')}#${imported.substringAfterLast('.')}"
            return InvocationTarget(imported, name, null, listOf(imported, memberId))
        }
        return null
    }

    private fun resolveReceiverOwner(
        call: KtCallExpression,
        receiver: KtExpression,
        names: KotlinSourceNames,
    ): String? =
        when (receiver) {
            is KtThisExpression -> names.classOwner(call)?.let(names::classFqn)
            is KtSuperExpression ->
                receiver.superTypeQualifier?.text?.let { names.qualifyType(it, call) }
                    ?: names.superClassFqn(call)
            is KtNameReferenceExpression ->
                resolveVariableType(receiver, receiver.getReferencedName())?.let {
                    names.qualifyType(it, call)
                } ?: names.resolveTypeOrObject(call, receiver.getReferencedName())
            is KtCallExpression ->
                (receiver.calleeExpression as? KtSimpleNameExpression)
                    ?.getReferencedName()
                    ?.let { names.resolveCallReceiverType(receiver, it) }
                    ?.let { names.qualifyType(it, call) }
            is KtDotQualifiedExpression -> names.resolveQualifiedReceiverOwner(call, receiver)
            else -> names.qualifyType(receiver.text, call)
        }

    private fun resolveVariableType(useSite: KtElement, name: String): String? {
        var scope = useSite.parent
        var insideMemberFunction = false
        while (scope != null) {
            if (scope is KtNamedFunction && scope.parent is KtClassBody) {
                insideMemberFunction = true
            }
            val type = variableTypeInScope(scope, useSite, name, insideMemberFunction)
            if (type != null) {
                return type
            }
            scope = scope.parent
        }
        return null
    }

    private fun variableTypeInScope(
        scope: PsiElement,
        useSite: KtElement,
        name: String,
        insideMemberFunction: Boolean,
    ): String? =
        when (scope) {
            is KtNamedFunction ->
                scope.valueParameters.firstOrNull { it.name == name }?.typeReference?.text
            is KtFunctionLiteral ->
                scope.valueParameters.firstOrNull { it.name == name }?.typeReference?.text
            is KtCatchClause ->
                scope.catchParameter?.takeIf { it.name == name }?.typeReference?.text
            is KtForExpression ->
                scope.loopParameter?.takeIf { it.name == name }?.typeReference?.text
            is KtBlockExpression ->
                scope.statements
                    .filterIsInstance<KtProperty>()
                    .lastOrNull { it.name == name && it.textOffset < useSite.textOffset }
                    ?.typeReference
                    ?.text
            is KtClass ->
                scope.declarations
                    .filterIsInstance<KtProperty>()
                    .firstOrNull { it.name == name }
                    ?.typeReference
                    ?.text
                    ?: scope.primaryConstructorParameters
                        .firstOrNull {
                            it.name == name && (it.hasValOrVar() || !insideMemberFunction)
                        }
                        ?.typeReference
                        ?.text
            is KtClassOrObject ->
                scope.declarations
                    .filterIsInstance<KtProperty>()
                    .firstOrNull { it.name == name }
                    ?.typeReference
                    ?.text
            is KtFile ->
                scope.declarations
                    .filterIsInstance<KtProperty>()
                    .firstOrNull { it.name == name }
                    ?.typeReference
                    ?.text
            else -> null
        }

    private fun KtElement.lineNumber(): Int {
        val document = containingFile.viewProvider.document ?: return 1
        return document.getLineNumber(textRange.startOffset) + 1
    }

    private fun KtElement.columnNumber(): Int {
        val document = containingFile.viewProvider.document ?: return 1
        val line = document.getLineNumber(textRange.startOffset)
        return textRange.startOffset - document.getLineStartOffset(line) + 1
    }

    private data class ResolvedSymbol(
        val fqn: String,
        val name: String,
        val line: Int,
        val column: Int,
        val kind: String,
        val ownerFqn: String? = null,
        val signature: String? = null,
        val arity: Int? = null,
        val aliases: List<String> = emptyList(),
    )

    private data class InvocationTarget(
        val symbolFqn: String,
        val name: String,
        val qualifier: String?,
        val candidates: List<String> = listOf(symbolFqn),
    )

    private companion object {
        const val LANGUAGE = "kotlin"
        const val BOOLEAN_PREFIX_LENGTH = 2
    }
}

private class KotlinSourceNames(
    private val file: KtFile,
    private val imports: Map<String, String> = emptyMap(),
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
            names += current.name ?: "<anonymous@${current.textOffset}>"
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
