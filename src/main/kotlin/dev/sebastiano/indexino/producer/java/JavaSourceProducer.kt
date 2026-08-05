package dev.sebastiano.indexino.producer.java

import com.sun.source.tree.BlockTree
import com.sun.source.tree.CatchTree
import com.sun.source.tree.ClassTree
import com.sun.source.tree.CompilationUnitTree
import com.sun.source.tree.EnhancedForLoopTree
import com.sun.source.tree.ExpressionTree
import com.sun.source.tree.ForLoopTree
import com.sun.source.tree.IdentifierTree
import com.sun.source.tree.ImportTree
import com.sun.source.tree.LambdaExpressionTree
import com.sun.source.tree.MemberSelectTree
import com.sun.source.tree.MethodInvocationTree
import com.sun.source.tree.MethodTree
import com.sun.source.tree.NewClassTree
import com.sun.source.tree.Tree
import com.sun.source.tree.TryTree
import com.sun.source.tree.VariableTree
import com.sun.source.util.JavacTask
import com.sun.source.util.TreePathScanner
import com.sun.source.util.TreeScanner
import com.sun.source.util.Trees
import dev.sebastiano.indexino.core.key.CodeIndexKey
import dev.sebastiano.indexino.core.record.CallArgumentRecord
import dev.sebastiano.indexino.core.record.CallSiteRecord
import dev.sebastiano.indexino.core.record.ReferenceRecord
import dev.sebastiano.indexino.core.record.ResourceDefinitionRecord
import dev.sebastiano.indexino.core.record.ResourceUsageRecord
import dev.sebastiano.indexino.core.record.SymbolRecord
import dev.sebastiano.indexino.core.store.CodeIndexStore
import dev.sebastiano.indexino.core.store.hasSymbol
import dev.sebastiano.indexino.producer.IndexBuildContext
import dev.sebastiano.indexino.producer.IndexProducer
import dev.sebastiano.indexino.producer.IndexedSource
import dev.sebastiano.indexino.producer.SourceRecordCleanup
import dev.sebastiano.indexino.producer.xml.ResourceMetadata
import java.net.URI
import javax.tools.Diagnostic
import javax.tools.DiagnosticCollector
import javax.tools.JavaFileObject
import javax.tools.SimpleJavaFileObject
import javax.tools.ToolProvider

internal class JavaSourceProducer : IndexProducer {
    override val id: String = "java-source"
    override val namespace: String = "sym"
    override val displayName: String = "JavaSourceProducer"

    override val progressTotal: (IndexBuildContext) -> Int = { context ->
        sourceFilesToProcess(context).size
    }

    override fun produce(context: IndexBuildContext, store: CodeIndexStore) {
        val affectedSources =
            ((context.changedSources + context.deletedSources).filter {
                    it.path.endsWith(".java")
                } + metadataDependentSources(context))
                .distinctBy { it.originId to it.path }
        SourceRecordCleanup.deleteLanguageOriginRecords(
            store,
            LANGUAGE,
            ".java",
            affectedSources.toSet(),
        )
        val javaFiles = sourceFilesToProcess(context)
        // Seed declarations from every changed file before final call materialization. The parser
        // writes deterministic keys, so the final pass overwrites equivalent symbol/reference facts
        // while letting caller files resolve parameter names from callees later in source order.
        javaFiles.forEach { source -> parse(source, context.readSource(source), store, context) }
        javaFiles.forEachIndexed { index, source ->
            context.reportFileProgress(index + 1, javaFiles.size, source)
            parse(source, context.readSource(source), store, context)
        }
    }

    private fun sourceFilesToProcess(context: IndexBuildContext): List<IndexedSource> =
        (context.changedSources.filter { it.path.endsWith(".java") } +
                metadataDependentSources(context))
            .distinctBy { it.originId to it.path }

    private fun metadataDependentSources(context: IndexBuildContext): List<IndexedSource> {
        val metadataModules =
            (context.changedSources + context.deletedSources).mapNotNull { source ->
                ResourceMetadata.metadataModule(source.path)?.let { source.originId to it }
            }
        if (metadataModules.isEmpty()) return emptyList()
        return context.sources.filter { source ->
            source.path.endsWith(".java") &&
                (source.originId to ResourceMetadata.moduleDirectory(source.path)) in
                    metadataModules
        }
    }

    private fun parse(
        indexedSource: IndexedSource,
        source: String,
        store: CodeIndexStore,
        context: IndexBuildContext,
    ) {
        val relativePath = indexedSource.path
        val compiler =
            checkNotNull(ToolProvider.getSystemJavaCompiler()) { "JDK compiler is unavailable" }
        val diagnostics = DiagnosticCollector<JavaFileObject>()
        val sourceFile = StringJavaFileObject(relativePath, source)
        val task =
            compiler.getTask(
                null,
                null,
                diagnostics,
                listOf("-proc:none"),
                null,
                listOf(sourceFile),
            ) as JavacTask
        val unit = task.parse().single()
        val error = diagnostics.diagnostics.firstOrNull { it.kind == Diagnostic.Kind.ERROR }
        check(error == null) { "$relativePath:${error?.lineNumber}: ${error?.getMessage(null)}" }
        JavaRecordScanner(
                indexedSource.originId,
                relativePath,
                ResourceMetadata.resourcePackage(context, indexedSource),
                unit,
                Trees.instance(task),
                store,
            )
            .scan(unit, Unit)
    }

    private class StringJavaFileObject(path: String, private val source: String) :
        SimpleJavaFileObject(
            URI.create("string:///" + path.replace(' ', '_')),
            JavaFileObject.Kind.SOURCE,
        ) {
        override fun getCharContent(ignoreEncodingErrors: Boolean): CharSequence = source
    }

    @Suppress("TooManyFunctions")
    private class JavaRecordScanner(
        private val originId: String,
        private val relativePath: String,
        private val defaultResourcePackage: String?,
        private val unit: CompilationUnitTree,
        private val trees: Trees,
        private val store: CodeIndexStore,
    ) : TreePathScanner<Unit, Unit>() {
        private val packageName = unit.packageName?.toString().orEmpty()
        private val imports = mutableMapOf<String, String>()
        private val staticImports = mutableMapOf<String, String>()
        private val staticWildcardImports = mutableListOf<String>()
        private val classOwners = ArrayDeque<String>()
        private val classMethodNames = ArrayDeque<Set<String>>()
        private val classFieldTypes = ArrayDeque<Map<String, String>>()
        private val classNestedTypes = ArrayDeque<Map<String, String>>()
        private val classSuperTypes = ArrayDeque<String>()
        private val variableScopes = ArrayDeque<MutableMap<String, String>>()
        private val methodOwners = ArrayDeque<String>()

        override fun visitImport(node: ImportTree, data: Unit?) {
            val imported = node.qualifiedIdentifier.toString()
            val target =
                if (node.isStatic && !imported.endsWith(".*")) {
                    "${imported.substringBeforeLast('.')}#${imported.substringAfterLast('.')}"
                } else {
                    imported
                }
            if (node.isStatic && imported.endsWith(".*")) {
                staticWildcardImports += imported.removeSuffix(".*")
            } else if (node.isStatic) {
                staticImports[imported.substringAfterLast('.')] = imported.substringBeforeLast('.')
            } else if (!imported.endsWith(".*")) {
                imports[imported.substringAfterLast('.')] = imported
            }
            reference(target, imported.substringAfterLast('.'), null, node, "import")
            super.visitImport(node, data)
        }

        override fun visitMemberSelect(node: MemberSelectTree, data: Unit?) {
            val parent = currentPath.parentPath?.leaf
            if (parent !is MemberSelectTree || parent.expression != node) {
                indexResourceUsage(node)
            }
            super.visitMemberSelect(node, data)
        }

        override fun visitIdentifier(node: IdentifierTree, data: Unit?) {
            indexStaticResourceUsage(node)
            super.visitIdentifier(node, data)
        }

        private fun indexStaticResourceUsage(node: IdentifierTree) {
            val parent = currentPath.parentPath?.leaf
            if (shouldSkipStaticResourceIdentifier(node, parent)) return
            val name = node.name.toString()
            if (variableScopes.reversed().any { scope -> name in scope }) return
            if (isClassResourceChain(node, parent)) return
            val explicitOwner = staticImports[name]
            val owners =
                if (explicitOwner != null) listOf(explicitOwner)
                else staticWildcardImports.distinct()
            if (owners.isEmpty()) return
            val start = position(node)
            owners.forEach { owner ->
                putStaticResourceUsage(owner, name, start, explicitOwner == null)
            }
        }

        private fun shouldSkipStaticResourceIdentifier(
            node: IdentifierTree,
            parent: Tree?,
        ): Boolean =
            generateSequence(currentPath.parentPath) { it.parentPath }
                .any { it.leaf is ImportTree } ||
                (parent is MemberSelectTree && parent.expression != node) ||
                (parent is MethodInvocationTree && parent.methodSelect == node) ||
                (parent is MemberSelectTree &&
                    parent.identifier.toString() in ResourceMetadata.RESOURCE_TYPES) ||
                (parent as? VariableTree)?.name == node.name ||
                (parent as? MethodTree)?.name == node.name

        private fun isClassResourceChain(node: IdentifierTree, parent: Tree?): Boolean {
            if (parent !is MemberSelectTree || parent.expression != node) return false
            val outermost =
                generateSequence(currentPath.parentPath) { it.parentPath }
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
            store.forEachPrefix("resdef:") { _, record ->
                if (
                    record is ResourceDefinitionRecord &&
                        record.packageName == resourcePackage &&
                        record.type == type &&
                        record.name == name
                ) {
                    found = true
                }
                true
            }
            return found
        }

        private fun indexResourceUsage(node: MemberSelectTree) {
            if (
                generateSequence(currentPath.parentPath) { it.parentPath }
                    .any { it.leaf is ImportTree }
            ) {
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
            val start = position(node)
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

        private fun memberSelectSegments(tree: Tree): List<String>? =
            when (tree) {
                is IdentifierTree -> listOf(tree.name.toString())
                is MemberSelectTree ->
                    memberSelectSegments(tree.expression)?.plus(tree.identifier.toString())
                else -> null
            }

        override fun visitClass(node: ClassTree, data: Unit?) {
            val name = node.simpleName.toString()
            val owner = classOwners.lastOrNull()
            val fqn =
                if (name.isBlank()) {
                    val position = position(node)
                    val anonymousName = "<anonymous@${position.line}:${position.column}>"
                    owner?.let { "$it.$anonymousName" } ?: qualify(anonymousName)
                } else {
                    owner?.let { "$it.$name" } ?: qualify(name)
                }
            if (name.isNotBlank()) {
                symbol(fqn, name, classKind(node.kind), node, ownerFqn = owner)
                if (
                    node.kind == Tree.Kind.CLASS &&
                        node.members.none { it is MethodTree && it.name.contentEquals("<init>") }
                ) {
                    symbol(
                        fqn = "$fqn#<init>",
                        name = name,
                        kind = "constructor",
                        tree = node,
                        ownerFqn = fqn,
                        arity = 0,
                    )
                }
            }
            classOwners.addLast(fqn)
            classMethodNames.addLast(
                node.members
                    .filterIsInstance<MethodTree>()
                    .map { it.name.toString() }
                    .filterNot { it == "<init>" }
                    .toSet()
            )
            val fields =
                node.members
                    .filterIsInstance<VariableTree>()
                    .mapNotNull { field ->
                        field.type?.toString()?.let { field.name.toString() to it }
                    }
                    .toMap()
            val nestedTypes =
                node.members
                    .filterIsInstance<ClassTree>()
                    .mapNotNull { nested ->
                        nested.simpleName.toString().takeIf(String::isNotBlank)?.let {
                            it to "$fqn.$it"
                        }
                    }
                    .toMap()
            classFieldTypes.addLast(fields)
            classNestedTypes.addLast(nestedTypes)
            classSuperTypes.addLast(node.extendsClause?.toString()?.let(::qualifyType).orEmpty())
            variableScopes.addLast(fields.toMutableMap())
            try {
                super.visitClass(node, data)
            } finally {
                variableScopes.removeLast()
                classSuperTypes.removeLast()
                classNestedTypes.removeLast()
                classFieldTypes.removeLast()
                classMethodNames.removeLast()
                classOwners.removeLast()
            }
        }

        override fun visitMethod(node: MethodTree, data: Unit?) {
            val owner = classOwners.lastOrNull() ?: return super.visitMethod(node, data)
            val constructor = node.name.contentEquals("<init>")
            val name = if (constructor) owner.substringAfterLast('.') else node.name.toString()
            val fqn = if (constructor) "$owner#<init>" else "$owner#$name"
            val signature =
                node.parameters.joinToString(prefix = "(", postfix = ")") { it.type.toString() }
            symbol(
                fqn = fqn,
                name = name,
                kind = if (constructor) "constructor" else "method",
                tree = node,
                ownerFqn = owner,
                signature = signature,
                arity = node.parameters.size,
                parameterNames = node.parameters.map { it.name.toString() },
                isVararg = node.parameters.lastOrNull()?.toString()?.contains("...") == true,
            )
            variableScopes.addLast(mutableMapOf())
            node.parameters.forEach {
                variableScopes.last()[it.name.toString()] = it.type.toString()
            }
            methodOwners.addLast(fqn)
            try {
                super.visitMethod(node, data)
            } finally {
                methodOwners.removeLast()
                variableScopes.removeLast()
            }
        }

        override fun visitVariable(node: VariableTree, data: Unit?) {
            val declaredType = node.type?.toString()
            val resolvedType =
                if (declaredType.isNullOrBlank() || declaredType == "var") {
                    (node.initializer as? NewClassTree)?.identifier?.toString()
                } else {
                    declaredType
                }
            resolvedType?.let { type ->
                variableScopes.lastOrNull()?.put(node.name.toString(), type)
            }
            if (currentPath.parentPath?.leaf is ClassTree) {
                val owner = classOwners.lastOrNull()
                if (owner != null) {
                    symbol("$owner#${node.name}", node.name.toString(), "field", node, owner)
                }
            }
            super.visitVariable(node, data)
        }

        override fun visitBlock(node: BlockTree, data: Unit?) {
            variableScopes.addLast(mutableMapOf())
            try {
                super.visitBlock(node, data)
            } finally {
                variableScopes.removeLast()
            }
        }

        override fun visitTry(node: TryTree, data: Unit?) {
            variableScopes.addLast(mutableMapOf())
            try {
                node.resources.forEach { scan(it, data) }
                scan(node.block, data)
            } finally {
                variableScopes.removeLast()
            }
            node.catches.forEach { scan(it, data) }
            scan(node.finallyBlock, data)
        }

        override fun scan(tree: Tree?, data: Unit?) {
            val transientScope =
                tree is ForLoopTree ||
                    tree is EnhancedForLoopTree ||
                    tree is LambdaExpressionTree ||
                    tree is CatchTree
            if (transientScope) {
                variableScopes.addLast(mutableMapOf())
            }
            try {
                super.scan(tree, data)
            } finally {
                if (transientScope) {
                    variableScopes.removeLast()
                }
            }
        }

        override fun visitMethodInvocation(node: MethodInvocationTree, data: Unit?) {
            val target = resolveInvocation(node.methodSelect)
            if (target != null) {
                reference(
                    target = target.symbolFqn,
                    name = target.name,
                    qualifier = target.qualifier,
                    tree = node,
                    context = "call",
                    arity = node.arguments.size,
                    candidates = target.candidates,
                )
            }
            call(node, node.arguments, target, node.methodSelect.toString().substringAfterLast('.'))
            super.visitMethodInvocation(node, data)
        }

        override fun visitNewClass(node: NewClassTree, data: Unit?) {
            val owner = qualifyConstructorType(node.identifier.toString())
            val target =
                InvocationTarget(
                    "$owner#<init>",
                    node.identifier.toString().substringAfterLast('.'),
                    null,
                    listOf("$owner#<init>"),
                )
            reference(
                target = target.symbolFqn,
                name = target.name,
                qualifier = null,
                tree = node,
                context = "call",
                arity = node.arguments.size,
                candidates = target.candidates,
            )
            call(node, node.arguments, target, target.name)
            super.visitNewClass(node, data)
        }

        private fun call(
            node: Tree,
            arguments: List<ExpressionTree>,
            target: InvocationTarget?,
            fallbackName: String,
        ) {
            val start = position(node)
            val end = endPosition(node)
            val identity = callIdentity(start)
            val parameterNames =
                target?.let { storedParameterNames(it.symbolFqn, arguments.size) }.orEmpty()
            val parent =
                generateSequence(currentPath.parentPath) { it.parentPath }
                    .map { it.leaf }
                    .firstOrNull { it is MethodInvocationTree || it is NewClassTree }
                    ?.let { parentNode -> callIdentity(position(parentNode)) }
            store.put(
                CodeIndexKey.call(identity),
                CallSiteRecord(
                    identity = identity,
                    calleeName = target?.name ?: fallbackName,
                    candidateSymbolFqns = target?.candidates.orEmpty(),
                    receiver = target?.qualifier,
                    enclosingSymbolFqn = methodOwners.lastOrNull(),
                    parentCallIdentity = parent,
                    relativeFile = relativePath,
                    originId = originId,
                    startLine = start.line,
                    startColumn = start.column,
                    startOffset = start.offset,
                    endLine = end.line,
                    endColumn = end.column,
                    endOffset = end.offset,
                    arguments =
                        arguments.mapIndexed { index, argument ->
                            val argumentStart = position(argument)
                            val argumentEnd = endPosition(argument)
                            CallArgumentRecord(
                                position = index,
                                kind = if (argument is LambdaExpressionTree) "LAMBDA" else "VALUE",
                                resolvedName = parameterNames.getOrNull(index),
                                startLine = argumentStart.line,
                                startColumn = argumentStart.column,
                                startOffset = argumentStart.offset,
                                endLine = argumentEnd.line,
                                endColumn = argumentEnd.column,
                                endOffset = argumentEnd.offset,
                                nestedCallIdentities = nestedCallIdentities(argument),
                            )
                        },
                    confidence = if (target == null) "UNRESOLVED" else "HEURISTIC",
                ),
            )
        }

        private fun nestedCallIdentities(argument: Tree): List<String> {
            val identities = mutableListOf<String>()
            object : TreeScanner<Unit, Unit>() {
                    override fun visitMethodInvocation(node: MethodInvocationTree, data: Unit?) {
                        identities += callIdentity(position(node))
                        return super.visitMethodInvocation(node, data)
                    }

                    override fun visitNewClass(node: NewClassTree, data: Unit?) {
                        identities += callIdentity(position(node))
                        return super.visitNewClass(node, data)
                    }
                }
                .scan(argument, Unit)
            return identities
        }

        private fun storedParameterNames(fqn: String, argumentCount: Int): List<String> {
            val candidates = mutableListOf<SymbolRecord>()
            store.forEachPrefix("sym:$fqn:") { _, record ->
                if (record is SymbolRecord && record.fqn == fqn && record.arity == argumentCount) {
                    candidates += record
                }
                true
            }
            return (candidates.singleOrNull { it.originId == originId }
                    ?: candidates.singleOrNull())
                ?.parameterNames
                .orEmpty()
        }

        private fun resolveInvocation(select: Tree): InvocationTarget? =
            when (select) {
                is IdentifierTree -> {
                    val name = select.name.toString()
                    val classOwner = classOwners.lastOrNull() ?: return null
                    val localOwner =
                        classOwners
                            .zip(classMethodNames)
                            .toList()
                            .asReversed()
                            .firstOrNull { name in it.second }
                            ?.first
                    val explicitStaticOwner = staticImports[name]
                    when {
                        localOwner != null -> invocationTarget(localOwner, name, null)
                        explicitStaticOwner != null ->
                            invocationTarget(explicitStaticOwner, name, null)
                        staticWildcardImports.isNotEmpty() ->
                            invocationTarget(
                                staticWildcardImports.first(),
                                name,
                                null,
                                staticWildcardImports,
                            )
                        else -> {
                            val superOwner =
                                classSuperTypes.lastOrNull()?.takeIf(String::isNotBlank)
                            val inheritedTarget = superOwner?.let { "$it#$name" }
                            if (inheritedTarget != null && store.hasSymbol(inheritedTarget)) {
                                invocationTarget(superOwner, name, null)
                            } else {
                                invocationTarget(
                                    classOwner,
                                    name,
                                    null,
                                    listOfNotNull(classOwner, superOwner),
                                )
                            }
                        }
                    }
                }
                is MemberSelectTree -> {
                    val name = select.identifier.toString()
                    val receiverType = resolveReceiverType(select.expression) ?: return null
                    invocationTarget(receiverType, name, select.expression.toString())
                }
                else -> null
            }

        private fun resolveReceiverType(receiver: Tree): String? =
            when (receiver) {
                is IdentifierTree -> {
                    val name = receiver.name.toString()
                    if (name == "this") {
                        classOwners.lastOrNull()
                    } else if (name == "super") {
                        classSuperTypes.lastOrNull()?.takeIf(String::isNotBlank)
                    } else {
                        variableScopes
                            .reversed()
                            .firstNotNullOfOrNull { it[name] }
                            ?.let(::qualifyType) ?: qualifyType(name)
                    }
                }
                is NewClassTree -> qualifyType(receiver.identifier.toString())
                is MemberSelectTree -> {
                    val selfReceiver = receiver.expression as? IdentifierTree
                    if (selfReceiver?.name?.toString() == "this") {
                        val fieldName = receiver.identifier.toString()
                        classFieldTypes.lastOrNull()?.get(fieldName)?.let(::qualifyType)
                    } else if (selfReceiver?.name?.toString() == "super") {
                        null
                    } else {
                        qualifyType(receiver.toString())
                    }
                }
                else -> null
            }

        private fun qualifyConstructorType(raw: String): String {
            val type = raw.substringBefore('<').removeSuffix("[]").trim()
            val outer = type.substringBefore('.')
            return if ('.' in type && outer.firstOrNull()?.isUpperCase() == true) {
                val suffix = type.removePrefix(outer)
                (imports[outer]
                    ?: classNestedTypes.reversed().firstNotNullOfOrNull { it[outer] }
                    ?: qualify(outer)) + suffix
            } else {
                qualifyType(type)
            }
        }

        private fun qualifyType(raw: String): String {
            val type = raw.substringBefore('<').removeSuffix("[]").trim()
            imports[type]?.let {
                return it
            }
            if ('.' in type) {
                return type
            }
            return classNestedTypes.reversed().firstNotNullOfOrNull { it[type] } ?: qualify(type)
        }

        private val qualify: (String) -> String = { name ->
            if (packageName.isBlank()) name else "$packageName.$name"
        }

        private fun invocationTarget(
            owner: String,
            name: String,
            qualifier: String?,
            candidateOwners: List<String> = listOf(owner),
        ): InvocationTarget {
            val direct = "$owner#$name"
            val propertyName = javaBeanPropertyName(name)
            return InvocationTarget(
                direct,
                name,
                qualifier,
                candidateOwners
                    .flatMap { candidateOwner ->
                        listOfNotNull(
                            "$candidateOwner#$name",
                            propertyName?.let { "$candidateOwner#$it" },
                        )
                    }
                    .distinct(),
            )
        }

        private fun symbol(
            fqn: String,
            name: String,
            kind: String,
            tree: Tree,
            ownerFqn: String? = null,
            signature: String? = null,
            arity: Int? = null,
            parameterNames: List<String> = emptyList(),
            isVararg: Boolean = false,
        ) {
            val position = position(tree)
            store.put(
                CodeIndexKey.symbolDefinition(
                    fqn,
                    originId,
                    relativePath,
                    position.line,
                    position.column,
                ),
                SymbolRecord(
                    fqn = fqn,
                    relativeFile = relativePath,
                    originId = originId,
                    line = position.line,
                    column = position.column,
                    kind = kind,
                    name = name,
                    language = LANGUAGE,
                    ownerFqn = ownerFqn,
                    signature = signature,
                    arity = arity,
                    parameterNames = parameterNames,
                    isVararg = isVararg,
                ),
            )
        }

        private fun reference(
            target: String,
            name: String,
            qualifier: String?,
            tree: Tree,
            context: String,
            arity: Int? = null,
            candidates: List<String> = listOf(target),
        ) {
            val position = position(tree)
            store.put(
                CodeIndexKey.ref(target, originId, relativePath, position.line, position.column),
                ReferenceRecord(
                    symbolFqn = target,
                    relativeFile = relativePath,
                    originId = originId,
                    line = position.line,
                    column = position.column,
                    context = context,
                    language = LANGUAGE,
                    referencedName = name,
                    qualifier = qualifier,
                    candidateSymbolFqns = candidates,
                    arity = arity,
                ),
            )
        }

        private fun position(tree: Tree): SourcePosition =
            sourcePosition(trees.sourcePositions.getStartPosition(unit, tree))

        private fun endPosition(tree: Tree): SourcePosition =
            sourcePosition(
                (trees.sourcePositions.getEndPosition(unit, tree) - 1).coerceAtLeast(
                    trees.sourcePositions.getStartPosition(unit, tree)
                )
            )

        private fun sourcePosition(offset: Long): SourcePosition {
            if (offset < 0) return SourcePosition(1, 1, 0)
            return SourcePosition(
                unit.lineMap.getLineNumber(offset).toInt(),
                unit.lineMap.getColumnNumber(offset).toInt(),
                offset.toInt(),
            )
        }

        private fun callIdentity(position: SourcePosition): String =
            "$originId:$relativePath:${position.offset}"

        private data class SourcePosition(val line: Int, val column: Int, val offset: Int)

        private data class InvocationTarget(
            val symbolFqn: String,
            val name: String,
            val qualifier: String?,
            val candidates: List<String>,
        )
    }

    private companion object {
        const val LANGUAGE = "java"
    }
}

private fun javaBeanPropertyName(name: String): String? {
    val stem =
        when {
            name.startsWith("get") && name.length > GETTER_PREFIX_LENGTH -> name.removePrefix("get")
            name.startsWith("set") && name.length > SETTER_PREFIX_LENGTH -> name.removePrefix("set")
            name.startsWith("is") && name.length > BOOLEAN_PREFIX_LENGTH -> name.removePrefix("is")
            else -> return null
        }
    return stem.replaceFirstChar { it.lowercaseChar() }
}

private const val GETTER_PREFIX_LENGTH = 3
private const val SETTER_PREFIX_LENGTH = 3
private const val BOOLEAN_PREFIX_LENGTH = 2

private fun classKind(kind: Tree.Kind): String =
    when (kind) {
        Tree.Kind.INTERFACE -> "interface"
        Tree.Kind.ENUM -> "enum"
        Tree.Kind.RECORD -> "record"
        Tree.Kind.ANNOTATION_TYPE -> "annotation"
        else -> "class"
    }
