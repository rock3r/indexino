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
import dev.sebastiano.indexino.core.record.CallArgumentRecord
import dev.sebastiano.indexino.core.record.ResourceUsageRecord
import dev.sebastiano.indexino.core.record.SymbolRecord
import dev.sebastiano.indexino.producer.IndexedSource
import java.net.URI
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.tools.Diagnostic
import javax.tools.DiagnosticCollector
import javax.tools.JavaFileObject
import javax.tools.SimpleJavaFileObject
import javax.tools.StandardJavaFileManager
import javax.tools.StandardLocation
import javax.tools.ToolProvider

internal class JavaParseInput(
    val source: IndexedSource,
    val defaultResourcePackage: String?,
    val readSource: () -> String,
)

internal class JavaParsedFile(val source: IndexedSource, internal val facts: JavaFileFacts)

internal class JavaParsedBatch(val files: List<JavaParsedFile>, val workerThreadName: String)

internal class JavaFileFacts(
    val symbols: List<SymbolRecord>,
    val references: List<JavaReferenceDraft>,
    val calls: List<JavaCallDraft>,
    val resourceUsages: List<JavaResourceUsageDraft>,
)

internal sealed interface JavaInvocationResolution {
    class Resolved(val target: JavaInvocationTarget) : JavaInvocationResolution

    class InheritedFallback(val superOwner: String, val classOwner: String, val name: String) :
        JavaInvocationResolution

    fun resolve(hasSymbol: (String) -> Boolean): JavaInvocationTarget =
        when (this) {
            is Resolved -> target
            is InheritedFallback -> {
                if (hasSymbol("$superOwner#$name")) {
                    invocationTarget(superOwner, name, null)
                } else {
                    invocationTarget(classOwner, name, null, listOf(classOwner, superOwner))
                }
            }
        }
}

internal class JavaInvocationTarget(
    val symbolFqn: String,
    val name: String,
    val qualifier: String?,
    val candidates: List<String>,
)

internal class JavaReferenceDraft(
    val target: JavaInvocationResolution,
    val context: String,
    val arity: Int?,
    val line: Int,
    val column: Int,
)

internal class JavaCallDraft(
    val identity: String,
    val fallbackName: String,
    val target: JavaInvocationResolution?,
    val enclosingSymbolFqn: String?,
    val parentCallIdentity: String?,
    val startLine: Int,
    val startColumn: Int,
    val startOffset: Int,
    val endLine: Int,
    val endColumn: Int,
    val endOffset: Int,
    val arguments: List<CallArgumentRecord>,
)

internal class JavaResourceUsageDraft(
    val record: ResourceUsageRecord,
    val requireDefinition: Boolean,
)

/**
 * Parses bounded multi-file Javac batches on worker threads. Each worker keeps one standard file
 * manager; each source is read and walked exactly once.
 */
internal class JavaSourceParser {
    private val compiler =
        checkNotNull(ToolProvider.getSystemJavaCompiler()) { "JDK compiler is unavailable" }

    fun parse(
        inputs: List<JavaParseInput>,
        filesPerBatch: Int = JAVA_FILES_PER_BATCH,
        workers: Int = defaultJavaParseWorkers(),
    ): List<JavaParsedBatch> {
        require(filesPerBatch > 0) { "filesPerBatch must be positive" }
        require(workers > 0) { "workers must be positive" }
        if (inputs.isEmpty()) return emptyList()

        val batches = inputs.chunked(filesPerBatch)
        val results = arrayOfNulls<JavaParsedBatch>(batches.size)
        val failures = arrayOfNulls<Throwable>(batches.size)
        val nextBatch = AtomicInteger()
        val stopped = AtomicBoolean()
        val workerCount = workers.coerceAtMost(batches.size)
        val threadIds = AtomicInteger()
        val executor =
            Executors.newFixedThreadPool(workerCount) { runnable ->
                Thread(runnable, "indexino-java-parse-${threadIds.incrementAndGet()}").apply {
                    isDaemon = true
                }
            }
        val completed = ExecutorCompletionService<Unit>(executor)
        try {
            repeat(workerCount) {
                completed.submit(
                    Callable {
                        checkNotNull(compiler.getStandardFileManager(null, null, null)).use {
                            fileManager ->
                            fileManager.setLocationFromPaths(
                                StandardLocation.CLASS_PATH,
                                emptyList(),
                            )
                            runWorker(batches, results, failures, nextBatch, stopped, fileManager)
                        }
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
        return results.map { batch -> checkNotNull(batch) { "Java batch was not parsed" } }
    }

    private fun runWorker(
        batches: List<List<JavaParseInput>>,
        results: Array<JavaParsedBatch?>,
        failures: Array<Throwable?>,
        nextBatch: AtomicInteger,
        stopped: AtomicBoolean,
        fileManager: StandardJavaFileManager,
    ) {
        while (true) {
            if (Thread.currentThread().isInterrupted) {
                throw InterruptedException("Java parsing interrupted")
            }
            if (stopped.get()) return
            val index = nextBatch.getAndIncrement()
            if (index >= batches.size) return
            try {
                results[index] = parseBatch(batches[index], fileManager)
            } catch (@Suppress("TooGenericExceptionCaught") failure: Throwable) {
                failures[index] = failure
                stopped.set(true)
                return
            }
        }
    }

    private fun parseBatch(
        batch: List<JavaParseInput>,
        fileManager: StandardJavaFileManager,
    ): JavaParsedBatch {
        val sourceFiles = batch.map { input ->
            StringJavaFileObject(input.source.path, input.readSource())
        }
        val diagnostics = DiagnosticCollector<JavaFileObject>()
        val task =
            compiler.getTask(
                null,
                fileManager,
                diagnostics,
                listOf("-proc:none"),
                null,
                sourceFiles,
            ) as JavacTask
        val units = task.parse().toList()
        check(units.size == sourceFiles.size) {
            "Javac returned ${units.size} units for ${sourceFiles.size} Java sources"
        }
        check(units.map { unit -> unit.sourceFile.toUri() } == sourceFiles.map { it.toUri() }) {
            "Javac returned compilation units in a different order"
        }
        val errorsBySource = mutableMapOf<URI, Diagnostic<out JavaFileObject>>()
        val detachedError =
            diagnostics.diagnostics.firstOrNull { diagnostic ->
                diagnostic.kind == Diagnostic.Kind.ERROR && diagnostic.source == null
            }
        diagnostics.diagnostics.forEach { diagnostic ->
            if (diagnostic.kind == Diagnostic.Kind.ERROR) {
                diagnostic.source?.let { source ->
                    errorsBySource.putIfAbsent(source.toUri(), diagnostic)
                }
            }
        }
        val trees = Trees.instance(task)
        return JavaParsedBatch(
            batch.mapIndexed { index, input ->
                val error = errorsBySource[sourceFiles[index].toUri()] ?: detachedError
                check(error == null) {
                    "${input.source.path}:${error?.lineNumber}: ${error?.getMessage(null)}"
                }
                val unit = units[index]
                val scanner =
                    JavaRecordScanner(
                        originId = input.source.originId,
                        relativePath = input.source.path,
                        defaultResourcePackage = input.defaultResourcePackage,
                        unit = unit,
                        trees = trees,
                    )
                scanner.scan(unit, Unit)
                JavaParsedFile(input.source, scanner.facts())
            },
            Thread.currentThread().name,
        )
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
    ) : TreePathScanner<Unit, Unit>() {
        private val symbols = mutableListOf<SymbolRecord>()
        private val references = mutableListOf<JavaReferenceDraft>()
        private val calls = mutableListOf<JavaCallDraft>()
        private val resourceUsages = mutableListOf<JavaResourceUsageDraft>()
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
        private val resourceUsageIndexer =
            JavaResourceUsageIndexer(
                originId = originId,
                relativePath = relativePath,
                defaultResourcePackage = defaultResourcePackage,
                packageName = packageName,
                imports = imports,
                staticImports = staticImports,
                staticWildcardImports = staticWildcardImports,
                variableScopes = variableScopes,
                positionOf = { tree ->
                    val start = position(tree)
                    JavaResourceUsageIndexer.SourcePosition(start.line, start.column, start.offset)
                },
                onUsage = { record, requireDefinition ->
                    resourceUsages += JavaResourceUsageDraft(record, requireDefinition)
                },
            )

        fun facts(): JavaFileFacts =
            JavaFileFacts(
                symbols = symbols.toList(),
                references = references.toList(),
                calls = calls.toList(),
                resourceUsages = resourceUsages.toList(),
            )

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
                resourceUsageIndexer.indexResourceUsage(node, currentPath)
            }
            super.visitMemberSelect(node, data)
        }

        override fun visitIdentifier(node: IdentifierTree, data: Unit?) {
            resourceUsageIndexer.indexStaticResourceUsage(node, currentPath)
            super.visitIdentifier(node, data)
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
                reference(target, node, "call", node.arguments.size)
            }
            call(node, node.arguments, target, node.methodSelect.toString().substringAfterLast('.'))
            super.visitMethodInvocation(node, data)
        }

        override fun visitNewClass(node: NewClassTree, data: Unit?) {
            val owner = qualifyConstructorType(node.identifier.toString())
            val target =
                JavaInvocationResolution.Resolved(
                    JavaInvocationTarget(
                        "$owner#<init>",
                        node.identifier.toString().substringAfterLast('.'),
                        null,
                        listOf("$owner#<init>"),
                    )
                )
            reference(target, node, "call", node.arguments.size)
            call(node, node.arguments, target, node.identifier.toString().substringAfterLast('.'))
            super.visitNewClass(node, data)
        }

        private fun call(
            node: Tree,
            arguments: List<ExpressionTree>,
            target: JavaInvocationResolution?,
            fallbackName: String,
        ) {
            val start = position(node)
            val end = endPosition(node)
            val identity = callIdentity(start)
            val parent =
                generateSequence(currentPath.parentPath) { it.parentPath }
                    .map { it.leaf }
                    .firstOrNull { it is MethodInvocationTree || it is NewClassTree }
                    ?.let { parentNode -> callIdentity(position(parentNode)) }
            calls +=
                JavaCallDraft(
                    identity = identity,
                    fallbackName = fallbackName,
                    target = target,
                    enclosingSymbolFqn = methodOwners.lastOrNull(),
                    parentCallIdentity = parent,
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
                                resolvedName = null,
                                startLine = argumentStart.line,
                                startColumn = argumentStart.column,
                                startOffset = argumentStart.offset,
                                endLine = argumentEnd.line,
                                endColumn = argumentEnd.column,
                                endOffset = argumentEnd.offset,
                                nestedCallIdentities = nestedCallIdentities(argument),
                            )
                        },
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

        private fun resolveInvocation(select: Tree): JavaInvocationResolution? =
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
                        localOwner != null ->
                            JavaInvocationResolution.Resolved(
                                invocationTarget(localOwner, name, null)
                            )
                        explicitStaticOwner != null ->
                            JavaInvocationResolution.Resolved(
                                invocationTarget(explicitStaticOwner, name, null)
                            )
                        staticWildcardImports.isNotEmpty() ->
                            JavaInvocationResolution.Resolved(
                                invocationTarget(
                                    staticWildcardImports.first(),
                                    name,
                                    null,
                                    staticWildcardImports,
                                )
                            )
                        else -> {
                            val superOwner =
                                classSuperTypes.lastOrNull()?.takeIf(String::isNotBlank)
                            if (superOwner != null) {
                                JavaInvocationResolution.InheritedFallback(
                                    superOwner,
                                    classOwner,
                                    name,
                                )
                            } else {
                                JavaInvocationResolution.Resolved(
                                    invocationTarget(classOwner, name, null, listOf(classOwner))
                                )
                            }
                        }
                    }
                }
                is MemberSelectTree -> {
                    val name = select.identifier.toString()
                    val receiverType = resolveReceiverType(select.expression) ?: return null
                    JavaInvocationResolution.Resolved(
                        invocationTarget(receiverType, name, select.expression.toString())
                    )
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
            symbols +=
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
            reference(
                JavaInvocationResolution.Resolved(
                    JavaInvocationTarget(target, name, qualifier, candidates)
                ),
                tree,
                context,
                arity,
            )
        }

        private fun reference(
            target: JavaInvocationResolution,
            tree: Tree,
            context: String,
            arity: Int? = null,
        ) {
            val position = position(tree)
            references +=
                JavaReferenceDraft(
                    target = target,
                    context = context,
                    arity = arity,
                    line = position.line,
                    column = position.column,
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
    }

    private companion object {
        const val LANGUAGE = "java"
    }
}

internal const val JAVA_FILES_PER_BATCH = 64

internal fun defaultJavaParseWorkers(): Int =
    Runtime.getRuntime().availableProcessors().coerceAtLeast(1)

private fun invocationTarget(
    owner: String,
    name: String,
    qualifier: String?,
    candidateOwners: List<String> = listOf(owner),
): JavaInvocationTarget {
    val direct = "$owner#$name"
    val propertyName = javaBeanPropertyName(name)
    return JavaInvocationTarget(
        direct,
        name,
        qualifier,
        candidateOwners
            .flatMap { candidateOwner ->
                listOfNotNull("$candidateOwner#$name", propertyName?.let { "$candidateOwner#$it" })
            }
            .distinct(),
    )
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
