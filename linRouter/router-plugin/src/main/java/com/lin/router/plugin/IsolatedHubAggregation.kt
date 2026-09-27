package com.lin.router.plugin

import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.google.devtools.ksp.gradle.KspAATask
import com.google.devtools.ksp.gradle.KspExtension
import org.gradle.api.Project
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.register

/**
 * 将 Hub 汇总接在业务 KSP 之后；不把 Hub 反向加入业务 KSP 的源文件依赖图。
 * 适配 KSP2 task API，兼容范围见发布说明。
 * @author pengshilin
 * @since 2026-09-27
 */
internal object IsolatedHubAggregation {
    /**
     * 安装独立汇总模式。只在配置阶段使用 Project，任务执行仅使用声明的输入输出。
     * @param project 当前应用或库模块；库模块只配置其模块身份。
     * @return 无返回值；错误的聚合模式或 KSP1 配置会令构建明确失败。
     */
    internal fun configure(project: Project): Unit = with(project) {
        pluginManager.withPlugin("com.google.devtools.ksp") {
            val ksp = extensions.getByType<KspExtension>()
            ksp.arg("routerModuleName", path)
            pluginManager.withPlugin("com.android.application") application@{
                val extra = extensions.extraProperties
                if (extra.has("linRouter.aggregate") && extra.get("linRouter.aggregate").toString() == "false") {
                    return@application
                }
                require(providers.gradleProperty("ksp.useKSP2").getOrElse("true").toBoolean()) {
                    "LinRouter isolated Hub requires KSP2. Use linRouter.hubMode=legacy for KSP1."
                }
                val currentPrefix = formatModuleName(path)
                val mode = providers.gradleProperty("linRouter.aggregationMode").getOrElse("auto")
                require(mode in setOf("auto", "single")) { "Unknown linRouter.aggregationMode: $mode" }
                val order = providers.gradleProperty("linRouter.loaderOrder").getOrElse("")
                    .split(',').map(String::trim).filter(String::isNotEmpty)
                val outputRoot = layout.buildDirectory
                extensions.getByType<ApplicationAndroidComponentsExtension>().onVariants { variant ->
                    require(ksp.arguments["routerAggregateModules"].isNullOrBlank()) {
                        "Remove manual routerAggregateModules when linRouter.hubMode=isolated."
                    }
                    val suffix = variant.name.replaceFirstChar(Char::uppercaseChar)
                    val hub = tasks.register<GenerateRouterHubTask>("generate${suffix}LinRouterHub") {
                        description = "Aggregates LinRouter loaders without reprocessing application sources."
                        group = "linRouter"
                        val original = tasks.named<KspAATask>("ksp${suffix}Kotlin")
                        dependsOn(original)
                        localLoaders.from(original.get().kspConfig.kotlinOutputDir.asFileTree.matching {
                            include("com/lin/router/generated/*RouterLoader.kt")
                            include("com/lin/router/generated/*InterceptorLoader.kt")
                        })
                        if (mode == "auto") dependencyClasspath.from(original.get().kspConfig.libraries)
                        currentModulePrefix.set(currentPrefix)
                        loaderOrder.set(order)
                        outputDirectory.set(outputRoot.dir("generated/linRouterHub/${variant.name}/kotlin"))
                    }
                    // 排除同时消除 KSP 对 Hub 任务的隐式依赖，避免 KSP -> Hub -> KSP 环。
                    ksp.excludedSources.from(hub)
                    variant.sources.kotlin?.addGeneratedSourceDirectory(hub) { it.outputDirectory }
                }
            }
        }
    }

    /**
     * 与 v1.1.2 编译器的模块命名保持一致。
     * @param path Gradle 项目路径。
     * @return 生成的 Loader 类名前缀。
     */
    internal fun formatModuleName(path: String): String = path.removePrefix(":").split(':')
        .filter(String::isNotEmpty).joinToString("_") { part ->
            part.replace(Regex("[^a-zA-Z0-9_]"), "").split('-', '_').joinToString("") {
                it.replaceFirstChar { char -> if (char.isLowerCase()) char.titlecase() else char.toString() }
            }
        }
}
