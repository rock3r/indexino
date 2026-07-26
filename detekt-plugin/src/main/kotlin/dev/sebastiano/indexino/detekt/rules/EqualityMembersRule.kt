package dev.sebastiano.indexino.detekt.rules

import com.intellij.psi.util.PsiTreeUtil
import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtSafeQualifiedExpression

public class EqualityMembersRule(config: Config) : Rule(config, DESCRIPTION) {
    override fun visitClass(klass: KtClass) {
        super.visitClass(klass)
        if (!klass.isPublicApiValueType()) return

        val properties = klass.equalityProperties()
        if (properties.isEmpty()) return

        val functions =
            klass.declarations
                .filterIsInstance<KtNamedFunction>()
                .associateBy(KtNamedFunction::getName)
        val missingFunctions = REQUIRED_FUNCTIONS.filterNot(functions::containsKey)
        if (missingFunctions.isNotEmpty()) {
            report(
                Finding(
                    entity = Entity.atName(klass),
                    message =
                        "${klass.name} is missing required functions: " +
                            "${missingFunctions.joinToString()}.",
                )
            )
        }

        functions["equals"]?.let { checkEquals(it, properties) }
        functions["hashCode"]?.let { checkDirectPropertyUsage(it, properties) }
        functions["toString"]?.let { checkDirectPropertyUsage(it, properties) }
    }

    private fun KtClass.isPublicApiValueType(): Boolean {
        val packageName = containingKtFile.packageFqName.asString()
        if (TARGET_PACKAGES.none { packageName == it || packageName.startsWith("$it.") }) {
            return false
        }
        if (
            isEnum() ||
                isInterface() ||
                isAnnotation() ||
                hasModifier(KtTokens.PRIVATE_KEYWORD) ||
                hasModifier(KtTokens.INTERNAL_KEYWORD)
        ) {
            return false
        }
        return name !in REFERENCE_TYPE_NAMES
    }

    private fun KtClass.equalityProperties(): Set<String> {
        val constructorProperties =
            primaryConstructor
                ?.valueParameters
                ?.filter { it.hasValOrVar() && it.isEqualityProperty() }
                ?.mapNotNull { it.name }
                .orEmpty()
        val bodyProperties =
            declarations
                .filterIsInstance<KtProperty>()
                .filter { it.isStoredProperty() && it.isEqualityProperty() }
                .mapNotNull(KtProperty::getName)
        return (constructorProperties + bodyProperties).toSet()
    }

    private fun org.jetbrains.kotlin.psi.KtModifierListOwner.isEqualityProperty(): Boolean =
        !hasModifier(KtTokens.PRIVATE_KEYWORD) &&
            !hasModifier(KtTokens.INTERNAL_KEYWORD) &&
            annotationEntries.none { it.shortName?.asString() == EXCLUDE_ANNOTATION }

    private fun KtProperty.isStoredProperty(): Boolean =
        initializer != null || hasDelegate() || getter == null

    private fun checkEquals(function: KtNamedFunction, properties: Set<String>) {
        val otherParameter = function.valueParameters.singleOrNull()?.name ?: return
        val comparedProperties =
            function.bodyExpression
                ?.let {
                    PsiTreeUtil.collectElementsOfType(it, KtDotQualifiedExpression::class.java)
                }
                ?.filter {
                    it.receiverExpression.isName(otherParameter) &&
                        it.selectorExpression is KtNameReferenceExpression
                }
                ?.mapNotNull {
                    (it.selectorExpression as? KtNameReferenceExpression)?.getReferencedName()
                }
                .orEmpty()
                .toSet() +
                function.bodyExpression
                    ?.let {
                        PsiTreeUtil.collectElementsOfType(it, KtSafeQualifiedExpression::class.java)
                    }
                    ?.filter {
                        it.receiverExpression.isName(otherParameter) &&
                            it.selectorExpression is KtNameReferenceExpression
                    }
                    ?.mapNotNull {
                        (it.selectorExpression as? KtNameReferenceExpression)?.getReferencedName()
                    }
                    .orEmpty()

        properties.filterNot(comparedProperties::contains).forEach { property ->
            report(
                Finding(
                    entity = Entity.atName(function),
                    message =
                        "Function equals must compare property $property through " +
                            "$otherParameter.$property.",
                )
            )
        }
    }

    private fun checkDirectPropertyUsage(function: KtNamedFunction, properties: Set<String>) {
        val referencedNames =
            function.bodyExpression
                ?.let {
                    PsiTreeUtil.collectElementsOfType(it, KtNameReferenceExpression::class.java)
                }
                ?.map(KtNameReferenceExpression::getReferencedName)
                .orEmpty()
                .toSet()
        properties.filterNot(referencedNames::contains).forEach { property ->
            report(
                Finding(
                    entity = Entity.atName(function),
                    message = "Function ${function.name} is missing property $property.",
                )
            )
        }
    }

    private fun org.jetbrains.kotlin.psi.KtExpression.isName(name: String): Boolean =
        this is KtNameReferenceExpression && getReferencedName() == name

    private companion object {
        const val DESCRIPTION: String =
            "Requires structural equals, hashCode, and toString on public Indexino value types."
        const val EXCLUDE_ANNOTATION: String = "ExcludeFromEquality"
        val REQUIRED_FUNCTIONS: Set<String> = linkedSetOf("equals", "hashCode", "toString")
        val REFERENCE_TYPE_NAMES: Set<String> =
            setOf("Indexino", "IndexSnapshot", "RefreshHandle", "IndexinoException")
        val TARGET_PACKAGES: Set<String> =
            setOf(
                "dev.sebastiano.indexino.model",
                "dev.sebastiano.indexino.api",
                "dev.sebastiano.indexino.plugin.api",
            )
    }
}
