package com.parsuomash.koin_navigator

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.parsuomash.koin_navigator.utils.KoinIcons
import org.jetbrains.kotlin.idea.caches.resolve.analyze
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid
import org.jetbrains.kotlin.resolve.calls.callUtil.getResolvedCall
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameSafe
import org.jetbrains.kotlin.resolve.lazy.BodyResolveMode.PARTIAL
import org.jetbrains.kotlin.utils.IDEAPluginsCompatibilityAPI

/**
 * Provides line markers for Koin inject function calls.
 */
class KoinLineMarkerProvider : LineMarkerProvider {
    /**
     * Returns a line marker info for the given element if it is a Koin inject function call.
     *
     * @param element The PSI element to check.
     * @return A LineMarkerInfo object or null if the element is not a Koin inject function call.
     */
    @OptIn(IDEAPluginsCompatibilityAPI::class)
    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        if (element is KtCallExpression) {
            val callExpression = element.calleeExpression ?: return null

            if (callExpression.text in listOf("inject", "get", "koinInject", "rememberKoinInject")) {
                val resolvedCall =
                    callExpression.getResolvedCall(callExpression.analyze(PARTIAL)) ?: return null
                val callableDescriptor = resolvedCall.resultingDescriptor

                if (callableDescriptor.fqNameSafe.asString() in listOf(
                        "org.koin.core.component.inject", // inject in body of normal class
                        "org.koin.android.ext.android.inject", // inject in body of activity
                        "org.koin.core.scope.Scope.get", // get in module dsl
                        "org.koin.core.component.get", // get in function of normal class
                        "org.koin.android.ext.android.get", // get in function of activity
                        "org.koin.ktor.ext.get", // get in ktor extension function
                        "org.koin.compose.koinInject", // koinInject in composable function
                        "org.koin.compose.rememberKoinInject", // rememberKoinInject in composable function
//                        "org.koin.androidx.viewmodel.ext.android.viewModel", // viewModel in body of activity
//                        "org.koin.androidx.compose.koinViewModel", // koinViewModel in composable function
                    )
                ) {
                    val typeArgument = resolvedCall.typeArguments.values.firstOrNull()
                    val genericType = typeArgument?.toString() ?: "Unknown"
                    var qualifier = "|DEFAULT|"

                    for ((key, value) in resolvedCall.valueArguments.entries) {
                        if (key.fqNameSafe.asString() == "org.koin.core.component.inject.qualifier") {
                            qualifier = value.toString()
                                .replace("qualifier", "")
                                .replace("named", "")
                            break
                        }
                    }
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
            }
        }
        return null
    }
}

/**
 * Navigate to the provider definition.
 */
@OptIn(IDEAPluginsCompatibilityAPI::class)
private fun navigateToProvider(element: KtCallExpression, genericType: String, qualifier: String) {
    // Here we need to find the module definition and navigate to the appropriate single/singleOf call
    val project = element.project
    val psiManager = PsiManager.getInstance(project)
    val projectFileIndex = ProjectFileIndex.getInstance(project)

    val ktFiles = mutableListOf<KtFile>()

    projectFileIndex.iterateContent { virtualFile ->
        if (virtualFile.extension == "kt") {
            val psiFile = psiManager.findFile(virtualFile)
            if (psiFile is KtFile) {
                ktFiles.add(psiFile)
            }
        }
        true
    }

    var isProviderDetect = false
    for (ktFile in ktFiles) {
        ktFile.accept(object : KtTreeVisitorVoid() {
            override fun visitCallExpression(expression: KtCallExpression) {
                super.visitCallExpression(expression)
                val resolvedCall = expression.getResolvedCall(expression.analyze(PARTIAL)) ?: return
                val callableDescriptor = resolvedCall.resultingDescriptor
                val fqName = callableDescriptor.fqNameSafe.asString()

                if (fqName in listOf(
                        "org.koin.core.module.Module.single",
                        "org.koin.core.module.Module.factory",
                        "org.koin.core.module.dsl.singleOf",
                        "org.koin.core.module.dsl.factoryOf",
                        "org.koin.dsl.ScopeDSL.scoped",
                        "org.koin.core.module.dsl.scopedOf",
//                        "org.koin.androidx.viewmodel.dsl.viewModel",
//                        "org.koin.androidx.viewmodel.dsl.viewModelOf",
                    ) && resolvedCall.typeArguments.values.firstOrNull()?.toString() == genericType
                ) {
                    for ((key, value) in resolvedCall.valueArguments.entries) {
                        if (key.fqNameSafe.asString() in listOf(
                                "org.koin.core.module.Module.single.qualifier",
                                "org.koin.core.module.Module.factory.qualifier",
                                "org.koin.dsl.ScopeDSL.scoped.qualifier",
                            )
                        ) {
                            val qualifierValue = value.toString()
                                .replace("qualifier", "")
                                .replace("named", "")

                            if (expression.navigateToProvider(qualifierValue)) {
                                isProviderDetect = true
                                return
                            }
                        }
                        if (key.fqNameSafe.asString() in listOf(
                                "org.koin.core.module.dsl.singleOf.options",
                                "org.koin.core.module.dsl.factoryOf.options",
                                "org.koin.core.module.dsl.scopedOf.options",
                            )
                        ) {
                            val qualifierValue = value.toString()
                                .replace("{", "")
                                .replace("}", "")
                                .split("\n")
                                .find { it.contains("qualifier") || it.contains("named") } ?: "|DEFAULT|"

                            val originalQualifier = qualifierValue.trim()
                                .replace("qualifier", "")
                                .replace("named", "")

                            if (expression.navigateToProvider(originalQualifier)) {
                                isProviderDetect = true
                                return
                            }
                        }
                    }
                }
            }

            private fun KtCallExpression.navigateToProvider(qualifierValue: String): Boolean {
                if (qualifierValue == qualifier) {
                    // Navigate to this call expression
                    navigate(true)
                    return true
                }
                return false
            }
        })
        if (isProviderDetect) break
    }
}
