package dev.sebastiano.indexino.detekt.rules

import com.intellij.psi.util.PsiTreeUtil
import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtForExpression
import org.jetbrains.kotlin.psi.KtFunction
import org.jetbrains.kotlin.psi.KtFunctionLiteral
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParenthesizedExpression
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtSafeQualifiedExpression
import org.jetbrains.kotlin.psi.KtThisExpression

public class EqualityMembersRule(config: Config) : Rule(config, DESCRIPTION) {
    override fun visitClass(klass: KtClass) {
        super.visitClass(klass)
        if (!klass.isPublicApiValueType()) return

        val properties = klass.equalityProperties()
        if (properties.isEmpty()) return

        val functions = REQUIRED_FUNCTIONS.associateWith { functionName ->
            klass.declarations.filterIsInstance<KtNamedFunction>().firstOrNull {
                it.isRequiredOverride(functionName)
            }
        }
        val missingFunctions = REQUIRED_FUNCTIONS.filter { functions[it] == null }
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

        functions["equals"]?.let { checkEquals(it, properties, klass.name) }
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

    private fun KtNamedFunction.isRequiredOverride(functionName: String): Boolean {
        if (name != functionName || !hasModifier(KtTokens.OVERRIDE_KEYWORD)) return false
        return when (functionName) {
            "equals" -> valueParameters.singleOrNull()?.typeReference?.text?.endsWith("?") == true
            "hashCode",
            "toString" -> valueParameters.isEmpty()
            else -> false
        }
    }

    private fun checkEquals(
        function: KtNamedFunction,
        properties: Set<String>,
        receiverLabel: String?,
    ) {
        val otherParameter = function.valueParameters.singleOrNull()?.name ?: return
        val comparedProperties =
            function.bodyExpression
                ?.let { PsiTreeUtil.collectElementsOfType(it, KtBinaryExpression::class.java) }
                ?.filter {
                    it.operationToken == KtTokens.EQEQ || it.operationToken == KtTokens.EXCLEQ
                }
                ?.flatMap { expression ->
                    properties.filter { property ->
                        expression.comparesReceiverAndOtherProperty(
                            function,
                            otherParameter,
                            property,
                            receiverLabel,
                        )
                    }
                }
                .orEmpty()
                .toSet()

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

    private fun KtBinaryExpression.comparesReceiverAndOtherProperty(
        equalsFunction: KtNamedFunction,
        otherParameter: String,
        property: String,
        receiverLabel: String?,
    ): Boolean {
        val left = left ?: return false
        val right = right ?: return false
        return (left.isReceiverProperty(equalsFunction, property, receiverLabel) &&
            right.isOtherProperty(equalsFunction, otherParameter, property)) ||
            (right.isReceiverProperty(equalsFunction, property, receiverLabel) &&
                left.isOtherProperty(equalsFunction, otherParameter, property))
    }

    private fun KtExpression.isReceiverProperty(
        equalsFunction: KtNamedFunction,
        property: String,
        receiverLabel: String?,
    ): Boolean =
        when (this) {
            is KtParenthesizedExpression ->
                expression?.isReceiverProperty(equalsFunction, property, receiverLabel) == true
            else ->
                (isName(property) &&
                    !isShadowed(property) &&
                    !isInsideReceiverLambda(equalsFunction) &&
                    isOwnedByClass(receiverLabel)) ||
                    (this is KtDotQualifiedExpression &&
                        receiverExpression.isCurrentReceiver(equalsFunction, receiverLabel) &&
                        selectorExpression?.isName(property) == true)
        }

    private fun KtExpression.isOwnedByClass(receiverLabel: String?): Boolean =
        generateSequence(parent) { it.parent }
            .filterIsInstance<KtClassOrObject>()
            .firstOrNull()
            ?.name == receiverLabel

    private fun KtExpression.isInsideReceiverLambda(equalsFunction: KtNamedFunction): Boolean =
        generateSequence(parent) { it.parent }
            .filterIsInstance<KtFunction>()
            .takeWhile { it !== equalsFunction }
            .any { it is KtFunctionLiteral && it.hasReceiverLambdaSyntax() }

    private fun KtExpression.isCurrentReceiver(
        equalsFunction: KtNamedFunction,
        receiverLabel: String?,
    ): Boolean =
        this is KtThisExpression &&
            ((text == "this" &&
                generateSequence(parent) { it.parent }
                    .filterIsInstance<KtClassOrObject>()
                    .firstOrNull()
                    ?.name == receiverLabel &&
                generateSequence(parent) { it.parent }
                    .filterIsInstance<KtFunction>()
                    .takeWhile { it !== equalsFunction }
                    .all {
                        (it is KtNamedFunction && it.receiverTypeReference == null) ||
                            (it is KtFunctionLiteral && !it.hasReceiverLambdaSyntax())
                    }) || (receiverLabel != null && text == "this@$receiverLabel"))

    private fun KtFunctionLiteral.hasReceiverLambdaSyntax(): Boolean {
        val lambda = parent as? KtLambdaExpression ?: return true
        val call =
            PsiTreeUtil.getParentOfType(lambda, KtCallExpression::class.java, false) ?: return true
        val calleeName = call.calleeExpression?.text
        val qualifiedCall = call.parent as? KtDotQualifiedExpression
        return calleeName == "with" ||
            (calleeName in setOf("run", "apply") &&
                qualifiedCall?.selectorExpression == call &&
                qualifiedCall.receiverExpression.text != "kotlin")
    }

    private fun KtExpression.isShadowed(
        property: String,
        ignoredFunction: KtFunction? = null,
    ): Boolean =
        generateSequence(parent) { it.parent }
            .any { ancestor ->
                when (ancestor) {
                    is KtBlockExpression ->
                        ancestor.statements.filterIsInstance<KtProperty>().any {
                            it.name == property && it.textOffset < textOffset
                        }
                    is KtFunction ->
                        ancestor !== ignoredFunction &&
                            ancestor.valueParameters.any { it.name == property }
                    is KtForExpression -> ancestor.loopParameter?.name == property
                    else -> false
                }
            }

    private fun KtExpression.isOtherProperty(
        equalsFunction: KtNamedFunction,
        otherParameter: String,
        property: String,
    ): Boolean =
        when (this) {
            is KtParenthesizedExpression ->
                expression?.isOtherProperty(equalsFunction, otherParameter, property) == true
            else ->
                ((this as? KtDotQualifiedExpression)?.let {
                    it.receiverExpression.isName(otherParameter) &&
                        !it.receiverExpression.isShadowed(otherParameter, equalsFunction) &&
                        it.selectorExpression?.isName(property) == true
                } == true) ||
                    ((this as? KtSafeQualifiedExpression)?.let {
                        it.receiverExpression.isName(otherParameter) &&
                            !it.receiverExpression.isShadowed(otherParameter, equalsFunction) &&
                            it.selectorExpression?.isName(property) == true
                    } == true)
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
