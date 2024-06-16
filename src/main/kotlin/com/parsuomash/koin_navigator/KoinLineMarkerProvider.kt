package com.parsuomash.koin_navigator

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.parsuomash.koin_navigator.utils.CacheHandler
import com.parsuomash.koin_navigator.utils.KoinIcons
import com.parsuomash.koin_navigator.utils.fileCache
import org.jetbrains.kotlin.idea.caches.resolve.analyze
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid
import org.jetbrains.kotlin.resolve.calls.callUtil.getResolvedCall
import org.jetbrains.kotlin.resolve.calls.model.ResolvedCall
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameSafe
import org.jetbrains.kotlin.resolve.lazy.BodyResolveMode.PARTIAL
import org.jetbrains.kotlin.utils.IDEAPluginsCompatibilityAPI

/**
 * Provides line markers for Koin inject function calls.
 */
class KoinLineMarkerProvider : LineMarkerProvider {
    private var cacheHandler: CacheHandler? = null

    /**
     * Returns a line marker info for the given element if it is a Koin inject function call.
     *
     * @param element The PSI element to check.
     * @return A LineMarkerInfo object or null if the element is not a Koin inject function call.
     */
    @OptIn(IDEAPluginsCompatibilityAPI::class)
    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        if (cacheHandler == null) cacheHandler = CacheHandler(element.project)
        if (element !is KtCallExpression) return null

        val callExpression = element.calleeExpression ?: return null

        if (callExpression.text !in listOf("inject", "get", "koinInject", "rememberKoinInject")) return null

        val resolvedCall = callExpression.getResolvedCall(callExpression.analyze(PARTIAL)) ?: return null
        val callableDescriptor = resolvedCall.resultingDescriptor
        val fqName = callableDescriptor.fqNameSafe.asString()

        if (fqName !in koinInjectFqNames) return null

        val genericType = resolvedCall.typeArguments.values.firstOrNull()?.toString() ?: "Unknown"

        if (genericType in listOf("Application", "Context", "SavedStateHandle")) return null

        val qualifier = resolvedCall.valueArguments.entries
            .find { it.key.fqNameSafe.asString() in koinInjectQualifier }
            ?.value
            ?.toString()
            ?.replace("qualifier", "")
            ?.replace("named", "")
            ?: "|DEFAULT|"

        return LineMarkerInfo(
            element,
            element.textRange,
            KoinIcons.NAVIGATOR,
            { "Navigate to $genericType provider" },
            { _, _ -> navigateToProvider(element, genericType, qualifier) },
            GutterIconRenderer.Alignment.RIGHT,
            { "" }
        )
    }

    /**
     * Navigate to the provider definition.
     */
    private fun navigateToProvider(element: KtCallExpression, genericType: String, qualifier: String) {
        // Here we need to find the module definition and navigate to the appropriate single/singleOf call
        val project = element.project

        for (ktFile in collectKtFiles(project)) {
            if (navigateInFile(ktFile, genericType, qualifier)) break
        }
    }

    private fun collectKtFiles(project: Project): List<KtFile> {
        val cacheKey = project.name
        return fileCache[cacheKey] ?: cacheHandler!!.updateCache().also {
            fileCache[cacheKey] = it
        }
    }
}

@OptIn(IDEAPluginsCompatibilityAPI::class)
private fun navigateInFile(
    ktFile: KtFile,
    genericType: String,
    qualifier: String
): Boolean {
    var isProviderDetected = false

    ktFile.accept(object : KtTreeVisitorVoid() {
        override fun visitCallExpression(expression: KtCallExpression) {
            super.visitCallExpression(expression)

            val resolvedCall = expression.getResolvedCall(expression.analyze(PARTIAL)) ?: return

            if (!isInsideModuleDSL(expression)) return

            val callableDescriptor = resolvedCall.resultingDescriptor
            val fqName = callableDescriptor.fqNameSafe.asString()

            if (fqName in koinProviderFqNames &&
                resolvedCall.typeArguments.values.firstOrNull()?.toString() == genericType
            ) {
                if (checkAndNavigate(resolvedCall, expression, qualifier)) {
                    isProviderDetected = true
                    return
                }
            }
        }
    })

    return isProviderDetected
}

private fun isInsideModuleDSL(expression: KtCallExpression): Boolean {
    var parent = expression.parent
    while (parent != null) {
        if (parent is KtCallExpression && parent.calleeExpression?.text == "module") {
            return true
        }
        parent = parent.parent
    }
    return false
}

private fun checkAndNavigate(resolvedCall: ResolvedCall<*>, expression: KtCallExpression, qualifier: String): Boolean {
    for ((key, value) in resolvedCall.valueArguments.entries) {
        val keyName = key.fqNameSafe.asString()
        if (keyName in koinProviderQualifier) {
            val qualifierValue = value.toString()
                .replace("qualifier", "")
                .replace("named", "")
            if (qualifierValue == qualifier) {
                expression.navigate(true)
                return true
            }
        }
        if (keyName in koinProviderOptions) {
            val qualifierValue = value.toString().extractQualifier()
            if (qualifierValue == qualifier) {
                expression.navigate(true)
                return true
            }
        }
    }
    return false
}

private fun String.extractQualifier(): String {
    val qualifier = this
        .replace("{", "")
        .replace("}", "")
        .splitByLine()
        .find { it.contains("qualifier") || it.contains("named") }

    return qualifier?.trim()
        ?.replace("qualifier", "")
        ?.replace("named", "")
        ?: "|DEFAULT|"
}

private fun String.splitByLine(): List<String> = split("\n")

private val koinInjectFqNames = listOf(
    "org.koin.core.component.inject", // inject in body of normal class
    "org.koin.android.ext.android.inject", // inject in body of activity
    "org.koin.core.scope.Scope.get", // get in module dsl
    "org.koin.core.component.get", // get in function of normal class
    "org.koin.android.ext.android.get", // get in function of activity
    "org.koin.ktor.ext.get", // get in ktor extension function
    "org.koin.compose.koinInject", // koinInject in composable function
    "org.koin.compose.rememberKoinInject", // rememberKoinInject in composable function
//    "org.koin.androidx.viewmodel.ext.android.viewModel", // viewModel in body of activity
//    "org.koin.androidx.compose.koinViewModel", // koinViewModel in composable function
)

private val koinProviderFqNames = listOf(
    "org.koin.core.module.Module.single",
    "org.koin.core.module.Module.factory",
    "org.koin.core.module.dsl.singleOf",
    "org.koin.core.module.dsl.factoryOf",
    "org.koin.dsl.ScopeDSL.scoped",
    "org.koin.core.module.dsl.scopedOf",
//    "org.koin.androidx.viewmodel.dsl.viewModel",
//    "org.koin.androidx.viewmodel.dsl.viewModelOf",
)

private val koinInjectQualifier = listOf(
    "org.koin.core.component.inject.qualifier",
    "org.koin.android.ext.android.inject.qualifier"
)

private val koinProviderQualifier = listOf(
    "org.koin.core.module.Module.single.qualifier",
    "org.koin.core.module.Module.factory.qualifier",
    "org.koin.dsl.ScopeDSL.scoped.qualifier",
)

private val koinProviderOptions = listOf(
    "org.koin.core.module.dsl.singleOf.options",
    "org.koin.core.module.dsl.factoryOf.options",
    "org.koin.core.module.dsl.scopedOf.options",
)
