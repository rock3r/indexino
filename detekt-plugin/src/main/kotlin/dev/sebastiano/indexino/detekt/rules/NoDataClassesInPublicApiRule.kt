package dev.sebastiano.indexino.detekt.rules

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtPsiUtil

public class NoDataClassesInPublicApiRule(config: Config) : Rule(config, DESCRIPTION) {
    override fun visitClass(klass: KtClass) {
        super.visitClass(klass)
        if (!klass.isData() || !klass.isPublicApiDeclaration()) return

        report(
            Finding(
                entity = Entity.atName(klass),
                message =
                    "Public API class ${klass.name} must be an ordinary final class, not a data class.",
            )
        )
    }

    private fun KtClass.isPublicApiDeclaration(): Boolean {
        val packageName = containingKtFile.packageFqName.asString()
        if (TARGET_PACKAGES.none { packageName == it || packageName.startsWith("$it.") })
            return false
        if (KtPsiUtil.isLocal(this)) return false

        if (hasNonPublicVisibility()) return false
        return generateSequence(parent) { it.parent }
            .filterIsInstance<KtDeclaration>()
            .none { it.hasNonPublicVisibility() }
    }

    private fun KtDeclaration.hasNonPublicVisibility(): Boolean =
        hasModifier(KtTokens.PRIVATE_KEYWORD) || hasModifier(KtTokens.INTERNAL_KEYWORD)

    private companion object {
        const val DESCRIPTION: String = "Forbids data classes in Indexino public API packages."
        val TARGET_PACKAGES: Set<String> =
            setOf(
                "dev.sebastiano.indexino.model",
                "dev.sebastiano.indexino.api",
                "dev.sebastiano.indexino.plugin.api",
            )
    }
}
