package dev.sebastiano.indexino.producer.java

import com.sun.source.tree.BlockTree
import com.sun.source.tree.CatchTree
import com.sun.source.tree.ClassTree
import com.sun.source.tree.CompilationUnitTree
import com.sun.source.tree.EnhancedForLoopTree
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
import com.sun.source.util.Trees
import dev.sebastiano.indexino.core.key.CodeIndexKey
import dev.sebastiano.indexino.core.record.ReferenceRecord
import dev.sebastiano.indexino.core.record.SymbolRecord
import dev.sebastiano.indexino.core.store.CodeIndexStore
import dev.sebastiano.indexino.core.store.hasSymbol
import dev.sebastiano.indexino.producer.IndexBuildContext
import dev.sebastiano.indexino.producer.IndexProducer
import dev.sebastiano.indexino.producer.SourceRecordCleanup
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
        context.sourceFiles.count { it.endsWith(".java") && it in context.changedSourceFiles }
    }

    override fun produce(context: IndexBuildContext, store: CodeIndexStore) {
        val affectedFiles =
            (context.changedSourceFiles + context.deletedSourceFiles).filterTo(linkedSetOf()) {
                it.endsWith(".java")
            }
        SourceRecordCleanup.deleteLanguageRecords(store, LANGUAGE, ".java", affectedFiles)
        val javaFiles =
            context.sourceFiles.filter { it.endsWith(".java") && it in context.changedSourceFiles }
        javaFiles.forEachIndexed { index, relativePath ->
            context.reportFileProgress(index + 1, javaFiles.size, relativePath)
            parse(relativePath, context.readSource(relativePath), store)
        }
    }

    private fun parse(relativePath: String, source: String, store: CodeIndexStore) {
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
        JavaRecordScanner(relativePath, unit, Trees.instance(task), store).scan(unit, Unit)
    }

    private class StringJavaFileObject(path: String, private val source: String) :
        SimpleJavaFileObject(
            URI.create("string:///" + path.replace(' ', '_')),
            JavaFileObject.Kind.SOURCE,
        ) {
        override fun getCharContent(ignoreEncodingErrors: Boolean): CharSequence = source
    }

    private class JavaRecordScanner(
        private val relativePath: String,
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
            )
            variableScopes.addLast(mutableMapOf())
            node.parameters.forEach {
                variableScopes.last()[it.name.toString()] = it.type.toString()
            }
            try {
                super.visitMethod(node, data)
            } finally {
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
            super.visitMethodInvocation(node, data)
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
        ) {
            val position = position(tree)
            store.put(
                CodeIndexKey.symbolDefinition(fqn, relativePath, position.line, position.column),
                SymbolRecord(
                    fqn = fqn,
                    relativeFile = relativePath,
                    line = position.line,
                    kind = kind,
                    name = name,
                    language = LANGUAGE,
                    ownerFqn = ownerFqn,
                    signature = signature,
                    arity = arity,
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
                CodeIndexKey.ref(target, relativePath, position.line, position.column),
                ReferenceRecord(
                    symbolFqn = target,
                    relativeFile = relativePath,
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

        private fun position(tree: Tree): SourcePosition {
            val offset = trees.sourcePositions.getStartPosition(unit, tree)
            if (offset < 0) {
                return SourcePosition(1, 1)
            }
            return SourcePosition(
                unit.lineMap.getLineNumber(offset).toInt(),
                unit.lineMap.getColumnNumber(offset).toInt(),
            )
        }

        private data class SourcePosition(val line: Int, val column: Int)

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
