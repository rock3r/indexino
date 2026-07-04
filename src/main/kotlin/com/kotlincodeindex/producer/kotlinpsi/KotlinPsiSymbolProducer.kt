package com.kotlincodeindex.producer.kotlinpsi

import com.kotlincodeindex.core.key.CodeIndexKey
import com.kotlincodeindex.core.record.ReferenceRecord
import com.kotlincodeindex.core.record.SymbolRecord
import com.kotlincodeindex.core.store.CodeIndexStore
import com.kotlincodeindex.parse.KotlinPsiParser
import com.kotlincodeindex.producer.IndexBuildContext
import com.kotlincodeindex.producer.IndexProducer
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtSimpleNameExpression

class KotlinPsiSymbolProducer : IndexProducer {
    override val id: String = "kotlin-psi-symbols"
    override val namespace: String = "sym"
    override val displayName: String = "KotlinPsiSymbolProducer"

    override fun produce(context: IndexBuildContext, store: CodeIndexStore) {
        deleteStaleSymbolKeys(store)
        KotlinPsiParser().use { parser ->
            val ktFiles = context.sourceFiles.filter { it.endsWith(".kt") }
            ktFiles.forEachIndexed { index, relativePath ->
                context.reportFileProgress(index + 1, ktFiles.size, relativePath)
                val file = parser.parseFile(relativePath, context.readSource(relativePath))
                val symbols = collectSymbols(file, relativePath)
                for (symbol in symbols) {
                    store.put(
                        CodeIndexKey.sym(symbol.fqn),
                        SymbolRecord(
                            fqn = symbol.fqn,
                            relativeFile = relativePath,
                            line = symbol.line,
                            kind = symbol.kind,
                            name = symbol.name,
                        ),
                    )
                }
                for (call in file.collectDescendantsOfType<KtCallExpression>()) {
                    val callee = extractCalleeName(call) ?: continue
                    val resolved = resolveSymbol(callee, symbols) ?: continue
                    val line = call.lineNumber()
                    val column = call.columnNumber()
                    store.put(
                        CodeIndexKey.ref(resolved.fqn, relativePath, line),
                        ReferenceRecord(
                            symbolFqn = resolved.fqn,
                            relativeFile = relativePath,
                            line = line,
                            column = column,
                        ),
                    )
                }
            }
        }
    }

    private fun deleteStaleSymbolKeys(store: CodeIndexStore) {
        store.prefixScan("sym:").forEach { (key, _) -> store.delete(key) }
        store.prefixScan("ref:").forEach { (key, _) -> store.delete(key) }
    }

    private fun collectSymbols(file: KtFile, relativePath: String): List<ResolvedSymbol> {
        val pkg = file.packageFqName.asString().takeIf { it.isNotBlank() }
        val results = mutableListOf<ResolvedSymbol>()
        for (cls in file.collectDescendantsOfType<KtClass>()) {
            val name = cls.name ?: continue
            val fqn = listOfNotNull(pkg, name).joinToString(".")
            results += ResolvedSymbol(fqn, name, cls.lineNumber(), "class")
        }
        for (fn in file.collectDescendantsOfType<KtNamedFunction>()) {
            val name = fn.name ?: continue
            val fqn = listOfNotNull(pkg, name).joinToString(".")
            results += ResolvedSymbol(fqn, name, fn.lineNumber(), "function")
        }
        return results
    }

    private fun resolveSymbol(callee: String, symbols: List<ResolvedSymbol>): ResolvedSymbol? =
        symbols.firstOrNull { it.name == callee }

    private fun extractCalleeName(call: KtCallExpression): String? {
        val callee = call.calleeExpression ?: return null
        return when (callee) {
            is KtSimpleNameExpression -> callee.getReferencedName()
            is KtDotQualifiedExpression ->
                (callee.selectorExpression as? KtSimpleNameExpression)?.getReferencedName()
            else -> null
        }
    }

    private fun KtNamedFunction.lineNumber(): Int = lineNumberFromOffset(textRange.startOffset)
    private fun KtClass.lineNumber(): Int = lineNumberFromOffset(textRange.startOffset)
    private fun KtCallExpression.lineNumber(): Int = lineNumberFromOffset(textRange.startOffset)
    private fun KtCallExpression.columnNumber(): Int {
        val doc = containingFile.viewProvider.document ?: return 1
        val line = doc.getLineNumber(textRange.startOffset)
        return textRange.startOffset - doc.getLineStartOffset(line) + 1
    }

    private fun org.jetbrains.kotlin.psi.KtElement.lineNumberFromOffset(offset: Int): Int {
        val doc = containingFile.viewProvider.document ?: return 1
        return doc.getLineNumber(offset) + 1
    }

    private data class ResolvedSymbol(
        val fqn: String,
        val name: String,
        val line: Int,
        val kind: String,
    )
}

private inline fun <reified T> org.jetbrains.kotlin.psi.KtElement.collectDescendantsOfType(): List<T> {
    val results = mutableListOf<T>()
    accept(object : org.jetbrains.kotlin.com.intellij.psi.PsiElementVisitor() {
        override fun visitElement(element: org.jetbrains.kotlin.com.intellij.psi.PsiElement) {
            if (element is T) {
                results += element
            }
            element.acceptChildren(this)
        }
    })
    return results
}
