package dev.sebastiano.indexino.detekt.rules

import com.intellij.psi.util.PsiTreeUtil
import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.RequiresAnalysisApi
import dev.detekt.api.Rule
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.resolution.singleFunctionCallOrNull
import org.jetbrains.kotlin.analysis.api.resolution.symbol
import org.jetbrains.kotlin.analysis.api.symbols.KaClassSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaPropertySymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaReceiverParameterSymbol
import org.jetbrains.kotlin.analysis.api.types.symbol
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtCatchClause
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtDestructuringDeclaration
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtForExpression
import org.jetbrains.kotlin.psi.KtFunction
import org.jetbrains.kotlin.psi.KtFunctionLiteral
import org.jetbrains.kotlin.psi.KtLabeledExpression
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParenthesizedExpression
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtSafeQualifiedExpression
import org.jetbrains.kotlin.psi.KtThisExpression
import org.jetbrains.kotlin.psi.KtTypeReference
import org.jetbrains.kotlin.psi.KtWhenExpression

public class EqualityMembersRule(config: Config) : Rule(config, DESCRIPTION), RequiresAnalysisApi {
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
        if (
            name != functionName ||
                !hasModifier(KtTokens.OVERRIDE_KEYWORD) ||
                receiverTypeReference != null
        ) {
            return false
        }
        return when (functionName) {
            "equals" -> hasNullableAnyParameter()
            "hashCode",
            "toString" -> valueParameters.isEmpty()
            else -> false
        }
    }

    private fun KtNamedFunction.hasNullableAnyParameter(): Boolean {
        val typeReference = valueParameters.singleOrNull()?.typeReference ?: return false
        return typeReference.semanticallyResolvesToNullableAny()
    }

    private fun KtTypeReference.semanticallyResolvesToNullableAny(): Boolean =
        analyze(this) {
            val resolvedType = this@semanticallyResolvesToNullableAny.type
            resolvedType.isMarkedNullable &&
                resolvedType.symbol?.classId?.asFqNameString() == "kotlin.Any"
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
        val structuralCallKind = structuralCallKind() ?: return false
        val qualifiedCall = parent as? KtQualifiedExpression
        if (
            structuralCallKind == StructuralCallKind.RECEIVER &&
                qualifiedCall?.selectorExpression == this &&
                valueArguments.size == 1
        ) {
            val receiver = qualifiedCall.receiverExpression
            val argument = valueArguments.singleOrNull()?.getArgumentExpression() ?: return false
            return (receiver.isReceiverProperty(equalsFunction, property, receiverClass) &&
                argument.isOtherProperty(
                    equalsFunction,
                    otherParameter,
                    property,
                    receiverClass,
                )) ||
                (argument.isReceiverProperty(equalsFunction, property, receiverClass) &&
                    receiver.isOtherProperty(
                        equalsFunction,
                        otherParameter,
                        property,
                        receiverClass,
                    ))
        }
        if (
            structuralCallKind != StructuralCallKind.JAVA_OBJECTS ||
                valueArguments.size != 2 ||
                qualifiedCall?.selectorExpression != this
        ) {
            return false
        }
        val first = valueArguments[0].getArgumentExpression() ?: return false
        val second = valueArguments[1].getArgumentExpression() ?: return false
        return (first.isReceiverProperty(equalsFunction, property, receiverClass) &&
            second.isOtherProperty(equalsFunction, otherParameter, property, receiverClass)) ||
            (second.isReceiverProperty(equalsFunction, property, receiverClass) &&
                first.isOtherProperty(equalsFunction, otherParameter, property, receiverClass))
    }

    private fun KtCallExpression.structuralCallKind(): StructuralCallKind? =
        analyze(this) {
            val symbol =
                resolveToCall()?.singleFunctionCallOrNull()?.symbol as? KaNamedFunctionSymbol
                    ?: return null
            val callableNames =
                (symbol.allOverriddenSymbols + symbol)
                    .mapNotNull { it.callableId?.asSingleFqName()?.asString() }
                    .toSet()
            when {
                JAVA_OBJECTS_EQUALS in callableNames -> StructuralCallKind.JAVA_OBJECTS
                KOTLIN_ANY_EQUALS in callableNames ||
                    callableNames.any { it in RECEIVER_COMPARISON_CALLABLES } ->
                    StructuralCallKind.RECEIVER
                else -> null
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
            right.isOtherProperty(equalsFunction, otherParameter, property, receiverClass)) ||
            (right.isReceiverProperty(equalsFunction, property, receiverClass) &&
                left.isOtherProperty(equalsFunction, otherParameter, property, receiverClass))
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
                (resolvesToPropertyOf(receiverClass, property) &&
                    !isInsideNestedReceiver(equalsFunction, receiverClass)) ||
                    (this is KtDotQualifiedExpression &&
                        receiverExpression.isCurrentReceiver(equalsFunction, receiverClass) &&
                        selectorExpression?.resolvesToPropertyOf(receiverClass, property) == true)
        }

    private fun KtExpression.resolvesToPropertyOf(
        receiverClass: KtClass,
        property: String,
    ): Boolean {
        if (this !is KtNameReferenceExpression) return false
        return analyze(this) {
            val resolvedProperty =
                mainReference.resolveToSymbol() as? KaPropertySymbol ?: return false
            val targetClass = receiverClass.symbol as? KaClassSymbol ?: return false
            resolvedProperty.name.asString() == property &&
                (resolvedProperty.containingSymbol as? KaClassSymbol)?.classId ==
                    targetClass.classId
        }
    }

    private fun KtExpression.isInsideNestedReceiver(
        equalsFunction: KtNamedFunction,
        receiverClass: KtClass,
    ): Boolean =
        generateSequence(parent) { it.parent }
            .filterIsInstance<KtFunction>()
            .takeWhile { it !== equalsFunction }
            .any {
                (it is KtFunctionLiteral &&
                    it.hasReceiverType(receiverClass) &&
                    it.hasForeignReceiverLambdaSyntax(equalsFunction, receiverClass)) ||
                    (it is KtNamedFunction &&
                        it.receiverTypeReference != null &&
                        it.hasReceiverType(receiverClass))
            }

    private fun KtFunctionLiteral.hasReceiverType(receiverClass: KtClass): Boolean =
        analyze(this) {
            val targetClass = receiverClass.symbol as? KaClassSymbol ?: return false
            (symbol.receiverParameter?.returnType?.symbol as? KaClassSymbol)?.isSameOrSubclassOf(
                targetClass
            ) == true
        }

    private fun KtNamedFunction.hasReceiverType(receiverClass: KtClass): Boolean =
        analyze(this) {
            val targetClass = receiverClass.symbol as? KaClassSymbol ?: return false
            ((symbol as? KaNamedFunctionSymbol)?.receiverParameter?.returnType?.symbol
                    as? KaClassSymbol)
                ?.isSameOrSubclassOf(targetClass) == true
        }

    private fun KaClassSymbol.isSameOrSubclassOf(targetClass: KaClassSymbol): Boolean =
        classId == targetClass.classId ||
            superTypes.any {
                (it.symbol as? KaClassSymbol)?.isSameOrSubclassOf(targetClass) == true
            }

    private fun KtExpression.isCurrentReceiver(
        equalsFunction: KtNamedFunction,
        receiverClass: KtClass,
    ): Boolean {
        if (this is KtParenthesizedExpression) {
            return expression?.isCurrentReceiver(equalsFunction, receiverClass) == true
        }
        if (this !is KtThisExpression) return false
        return analyze(this) {
            val targetClass = receiverClass.symbol as? KaClassSymbol ?: return false
            when (val resolvedReceiver = instanceReference.mainReference.resolveToSymbol()) {
                is KaReceiverParameterSymbol ->
                    resolvedReceiver.returnType.symbol?.classId == targetClass.classId &&
                        ((text == "this@${receiverClass.name}" &&
                            generateSequence(parent) { it.parent }
                                .takeWhile { it !== equalsFunction }
                                .filterIsInstance<KtLabeledExpression>()
                                .none { it.getLabelName() == receiverClass.name }) ||
                            !isInsideNestedReceiver(equalsFunction, receiverClass))
                is KaClassSymbol -> resolvedReceiver.classId == targetClass.classId
                else -> false
            }
        }
    }

    private fun KtFunctionLiteral.hasForeignReceiverLambdaSyntax(
        equalsFunction: KtNamedFunction,
        receiverClass: KtClass,
    ): Boolean {
        val lambda = parent as? KtLambdaExpression ?: return true
        if (!hasExtensionReceiver()) return false
        val call =
            PsiTreeUtil.getParentOfType(lambda, KtCallExpression::class.java, false) ?: return true
        val qualifiedCall = call.parent as? KtQualifiedExpression
        if (qualifiedCall?.selectorExpression == call) {
            if (qualifiedCall.receiverExpression.text == "kotlin") {
                val receiverArguments = call.receiverLambdaArguments()
                if (receiverArguments.size > 1) return true
                val receiver = receiverArguments.singleOrNull() ?: return false
                return !receiver.isCurrentReceiver(equalsFunction, receiverClass)
            }
            val receiver =
                if (call.hasExtensionReceiver()) {
                    qualifiedCall.receiverExpression
                } else {
                    val receiverArguments = call.receiverLambdaArguments()
                    if (receiverArguments.size > 1) return true
                    receiverArguments.singleOrNull() ?: qualifiedCall.receiverExpression
                }
            return !receiver.isCurrentReceiver(equalsFunction, receiverClass)
        }
        val receiverArguments = call.receiverLambdaArguments()
        if (receiverArguments.size > 1) return true
        val receiver = receiverArguments.singleOrNull() ?: return false
        return !receiver.isCurrentReceiver(equalsFunction, receiverClass)
    }

    private fun KtCallExpression.receiverLambdaArguments(): List<KtExpression> =
        valueArguments
            .asSequence()
            .mapNotNull { it.getArgumentExpression() }
            .filter { it !is KtLambdaExpression }
            .toList()

    private fun KtCallExpression.hasExtensionReceiver(): Boolean =
        analyze(this) {
            resolveToCall()?.singleFunctionCallOrNull()?.symbol?.receiverParameter != null
        }

    private fun KtFunctionLiteral.hasExtensionReceiver(): Boolean =
        analyze(this) { symbol.receiverParameter?.owningCallableSymbol == symbol }

    private fun KtExpression.isShadowed(
        property: String,
        ignoredFunction: KtFunction? = null,
    ): Boolean =
        generateSequence(parent) { it.parent }
            .any { ancestor ->
                when (ancestor) {
                    is KtBlockExpression ->
                        ancestor.statements.any { statement ->
                            when (statement) {
                                is KtProperty ->
                                    statement.name == property &&
                                        statement.textOffset < textOffset &&
                                        statement.initializer?.let { initializer ->
                                            PsiTreeUtil.isAncestor(initializer, this, false)
                                        } != true
                                is KtDestructuringDeclaration ->
                                    statement.entries.any { it.name == property } &&
                                        statement.textOffset < textOffset &&
                                        statement.initializer?.let { initializer ->
                                            PsiTreeUtil.isAncestor(initializer, this, false)
                                        } != true
                                else -> false
                            }
                        }
                    is KtFunction ->
                        ancestor !== ignoredFunction &&
                            (ancestor.valueParameters.any { parameter ->
                                parameter.name == property ||
                                    parameter.destructuringDeclaration?.entries?.any {
                                        it.name == property
                                    } == true
                            } ||
                                (ancestor is KtFunctionLiteral &&
                                    property == "it" &&
                                    ancestor.hasImplicitItParameter()))
                    is KtWhenExpression ->
                        ancestor.subjectVariable?.let { subject ->
                            subject.name == property &&
                                subject.initializer?.let { initializer ->
                                    PsiTreeUtil.isAncestor(initializer, this, false)
                                } != true
                        } == true
                    is KtForExpression ->
                        (ancestor.loopParameter?.name == property ||
                            ancestor.destructuringDeclaration?.entries?.any {
                                it.name == property
                            } == true) &&
                            ancestor.body?.let { PsiTreeUtil.isAncestor(it, this, false) } == true
                    is KtCatchClause ->
                        ancestor.catchParameter?.name == property &&
                            ancestor.catchBody?.let { PsiTreeUtil.isAncestor(it, this, false) } ==
                                true
                    is KtClassOrObject ->
                        ignoredFunction != null &&
                            PsiTreeUtil.isAncestor(ignoredFunction, ancestor, false) &&
                            (ancestor.declarations.filterIsInstance<KtProperty>().any {
                                it.name == property
                            } ||
                                (ancestor as? KtClass)?.primaryConstructorParameters?.any {
                                    it.hasValOrVar() && it.name == property
                                } == true)
                    else -> false
                }
            }

    private fun KtFunctionLiteral.hasImplicitItParameter(): Boolean =
        valueParameters.isEmpty() &&
            analyze(this) { symbol.valueParameters.singleOrNull()?.name?.asString() == "it" }

    private fun KtExpression.isOtherProperty(
        equalsFunction: KtNamedFunction,
        otherParameter: String,
        property: String,
        receiverClass: KtClass,
    ): Boolean =
        when (this) {
            is KtParenthesizedExpression ->
                expression?.isOtherProperty(
                    equalsFunction,
                    otherParameter,
                    property,
                    receiverClass,
                ) == true
            else ->
                ((this as? KtDotQualifiedExpression)?.let {
                    it.receiverExpression.resolvesToEqualsParameter(
                        equalsFunction,
                        otherParameter,
                    ) &&
                        !it.receiverExpression.isShadowed(otherParameter, equalsFunction) &&
                        it.selectorExpression?.resolvesToPropertyOf(receiverClass, property) == true
                } == true) ||
                    ((this as? KtSafeQualifiedExpression)?.let {
                        it.receiverExpression.resolvesToEqualsParameter(
                            equalsFunction,
                            otherParameter,
                        ) &&
                            !it.receiverExpression.isShadowed(otherParameter, equalsFunction) &&
                            it.selectorExpression?.resolvesToPropertyOf(receiverClass, property) ==
                                true
                    } == true)
        }

    private fun KtExpression.resolvesToEqualsParameter(
        equalsFunction: KtNamedFunction,
        parameterName: String,
    ): Boolean {
        if (this is KtParenthesizedExpression) {
            return expression?.resolvesToEqualsParameter(equalsFunction, parameterName) == true
        }
        if (this !is KtNameReferenceExpression) return false
        val parameter =
            equalsFunction.valueParameters.singleOrNull { it.name == parameterName } ?: return false
        return analyze(this) { mainReference.resolveToSymbol() == parameter.symbol }
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
        const val JAVA_OBJECTS_EQUALS: String = "java.util.Objects.equals"
        const val KOTLIN_ANY_EQUALS: String = "kotlin.Any.equals"
        val RECEIVER_COMPARISON_CALLABLES: Set<String> =
            setOf("kotlin.collections.contentEquals", "kotlin.collections.contentDeepEquals")
    }

    private enum class StructuralCallKind {
        RECEIVER,
        JAVA_OBJECTS,
    }
}
