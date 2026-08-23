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
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParenthesizedExpression
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtSafeQualifiedExpression
import org.jetbrains.kotlin.psi.KtThisExpression
import org.jetbrains.kotlin.psi.KtTypeAlias

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

        functions["equals"]?.let { checkEquals(it, properties, klass) }
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
            "equals" -> hasNullableAnyParameter()
            "hashCode",
            "toString" -> valueParameters.isEmpty()
            else -> false
        }
    }

    private fun KtNamedFunction.hasNullableAnyParameter(): Boolean {
        val typeText =
            valueParameters.singleOrNull()?.typeReference?.typeElement?.text ?: return false
        return containingKtFile.resolvesToNullableAny(typeText, false, mutableSetOf())
    }

    private fun org.jetbrains.kotlin.psi.KtFile.resolvesToNullableAny(
        typeText: String,
        nullableAlias: Boolean,
        visitedAliases: MutableSet<String>,
    ): Boolean {
        val nullable = nullableAlias || typeText.endsWith("?")
        val typeName = typeText.removeSuffix("?")
        if (typeName == "kotlin.Any") return nullable
        if (typeName == "Any") {
            val shadowsDefaultAny =
                declarations.filterIsInstance<KtNamedDeclaration>().any { it.name == "Any" } ||
                    importDirectives.any {
                        it.aliasName == "Any" ||
                            (it.aliasName == null &&
                                it.importedFqName?.shortName()?.asString() == "Any" &&
                                it.importedFqName?.asString() != "kotlin.Any")
                    }
            return nullable && !shadowsDefaultAny
        }
        if (
            importDirectives.any {
                it.aliasName == typeName && it.importedFqName?.asString() == "kotlin.Any"
            }
        ) {
            return nullable
        }
        if (!visitedAliases.add(typeName)) return false
        val aliasedType =
            declarations
                .filterIsInstance<KtTypeAlias>()
                .firstOrNull { it.name == typeName }
                ?.getTypeReference()
                ?.text ?: return false
        return resolvesToNullableAny(aliasedType, nullable, visitedAliases)
    }

    private fun checkEquals(
        function: KtNamedFunction,
        properties: Set<String>,
        receiverClass: KtClass,
    ) {
        val otherParameter = function.valueParameters.singleOrNull()?.name ?: return
        val body = function.bodyExpression
        val binaryComparedProperties =
            body
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
                            receiverClass,
                        )
                    }
                }
                .orEmpty()
        val callComparedProperties =
            body
                ?.let { PsiTreeUtil.collectElementsOfType(it, KtCallExpression::class.java) }
                ?.flatMap { call ->
                    properties.filter { property ->
                        call.comparesReceiverAndOtherProperty(
                            function,
                            otherParameter,
                            property,
                            receiverClass,
                        )
                    }
                }
                .orEmpty()
        val comparedProperties = (binaryComparedProperties + callComparedProperties).toSet()

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

    private fun KtCallExpression.comparesReceiverAndOtherProperty(
        equalsFunction: KtNamedFunction,
        otherParameter: String,
        property: String,
        receiverClass: KtClass,
    ): Boolean {
        val calleeName = calleeExpression?.text ?: return false
        val qualifiedCall = parent as? KtDotQualifiedExpression
        if (
            calleeName in setOf("equals", "contentEquals", "contentDeepEquals") &&
                qualifiedCall?.selectorExpression == this &&
                valueArguments.size == 1
        ) {
            val receiver = qualifiedCall.receiverExpression
            val argument = valueArguments.singleOrNull()?.getArgumentExpression() ?: return false
            return (receiver.isReceiverProperty(equalsFunction, property, receiverClass) &&
                argument.isOtherProperty(equalsFunction, otherParameter, property)) ||
                (argument.isReceiverProperty(equalsFunction, property, receiverClass) &&
                    receiver.isOtherProperty(equalsFunction, otherParameter, property))
        }
        if (
            calleeName != "equals" ||
                valueArguments.size != 2 ||
                qualifiedCall?.selectorExpression != this ||
                !qualifiedCall.receiverExpression.isJavaUtilObjects()
        ) {
            return false
        }
        val first = valueArguments[0].getArgumentExpression() ?: return false
        val second = valueArguments[1].getArgumentExpression() ?: return false
        return (first.isReceiverProperty(equalsFunction, property, receiverClass) &&
            second.isOtherProperty(equalsFunction, otherParameter, property)) ||
            (second.isReceiverProperty(equalsFunction, property, receiverClass) &&
                first.isOtherProperty(equalsFunction, otherParameter, property))
    }

    private fun KtExpression.isJavaUtilObjects(): Boolean {
        if (text == "java.util.Objects") return true
        val objectsImport =
            containingKtFile.importDirectives.firstOrNull {
                it.importedFqName?.asString() == "java.util.Objects" ||
                    (it.isAllUnder && it.importedFqName?.asString() == "java.util")
            } ?: return false
        val importedName = objectsImport.aliasName ?: "Objects"
        if (text != importedName || isShadowed(importedName)) return false
        return containingKtFile.declarations.filterIsInstance<KtNamedDeclaration>().none {
            it.name == importedName
        }
    }

    private fun KtBinaryExpression.comparesReceiverAndOtherProperty(
        equalsFunction: KtNamedFunction,
        otherParameter: String,
        property: String,
        receiverClass: KtClass,
    ): Boolean {
        val left = left ?: return false
        val right = right ?: return false
        return (left.isReceiverProperty(equalsFunction, property, receiverClass) &&
            right.isOtherProperty(equalsFunction, otherParameter, property)) ||
            (right.isReceiverProperty(equalsFunction, property, receiverClass) &&
                left.isOtherProperty(equalsFunction, otherParameter, property))
    }

    private fun KtExpression.isReceiverProperty(
        equalsFunction: KtNamedFunction,
        property: String,
        receiverClass: KtClass,
    ): Boolean =
        when (this) {
            is KtParenthesizedExpression ->
                expression?.isReceiverProperty(equalsFunction, property, receiverClass) == true
            else ->
                (isName(property) &&
                    !isShadowed(property) &&
                    !isInsideNestedReceiver(equalsFunction) &&
                    isOwnedByClass(receiverClass)) ||
                    (this is KtDotQualifiedExpression &&
                        receiverExpression.isCurrentReceiver(equalsFunction, receiverClass) &&
                        selectorExpression?.isName(property) == true)
        }

    private fun KtExpression.isOwnedByClass(receiverClass: KtClass): Boolean =
        generateSequence(parent) { it.parent }.filterIsInstance<KtClassOrObject>().firstOrNull() ===
            receiverClass

    private fun KtExpression.isInsideNestedReceiver(equalsFunction: KtNamedFunction): Boolean =
        generateSequence(parent) { it.parent }
            .filterIsInstance<KtFunction>()
            .takeWhile { it !== equalsFunction }
            .any {
                (it is KtFunctionLiteral && it.hasReceiverLambdaSyntax()) ||
                    (it is KtNamedFunction && it.receiverTypeReference != null)
            }

    private fun KtExpression.isCurrentReceiver(
        equalsFunction: KtNamedFunction,
        receiverClass: KtClass,
    ): Boolean =
        this is KtThisExpression &&
            ((text == "this" &&
                generateSequence(parent) { it.parent }
                    .filterIsInstance<KtClassOrObject>()
                    .firstOrNull() === receiverClass &&
                generateSequence(parent) { it.parent }
                    .filterIsInstance<KtFunction>()
                    .takeWhile { it !== equalsFunction }
                    .all {
                        (it is KtNamedFunction && it.receiverTypeReference == null) ||
                            (it is KtFunctionLiteral && !it.hasReceiverLambdaSyntax())
                    }) || (receiverClass.name != null && text == "this@${receiverClass.name}"))

    private fun KtFunctionLiteral.hasReceiverLambdaSyntax(): Boolean {
        val lambda = parent as? KtLambdaExpression ?: return true
        val call =
            PsiTreeUtil.getParentOfType(lambda, KtCallExpression::class.java, false) ?: return true
        val calleeName = call.calleeExpression?.text
        val qualifiedCall = call.parent as? KtDotQualifiedExpression
        val aliasesNonReceiverRun =
            calleeName == "with" &&
                containingKtFile.importDirectives.any {
                    it.aliasName == "with" && it.importedFqName?.asString() == "kotlin.run"
                }
        return (calleeName == "with" && !aliasesNonReceiverRun) ||
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
                    it.receiverExpression.isPossiblyParenthesizedName(otherParameter) &&
                        !it.receiverExpression.isShadowed(otherParameter, equalsFunction) &&
                        it.selectorExpression?.isName(property) == true
                } == true) ||
                    ((this as? KtSafeQualifiedExpression)?.let {
                        it.receiverExpression.isPossiblyParenthesizedName(otherParameter) &&
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

    private fun KtExpression.isPossiblyParenthesizedName(name: String): Boolean =
        when (this) {
            is KtParenthesizedExpression -> expression?.isPossiblyParenthesizedName(name) == true
            else -> isName(name)
        }

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
