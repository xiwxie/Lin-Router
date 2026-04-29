package com.lin.router.plugin

import com.android.build.api.dsl.CommonExtension
import com.google.devtools.ksp.gradle.KspExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import java.util.Properties

/**
 * LinRouter 官方插件 (Standalone 版)
 * 内置全轨迹日志监控，支持高效排障
 */
class LinRouterPlugin : Plugin<Project> {

    override fun apply(target: Project) {
        with(target) {
            // 默认开启详细日志以备排障
            val isVerbose = findProperty("linRouter.verbose")?.toString()?.toBoolean() 
                ?: findLocalProperty("linRouter.verbose")?.toBoolean() 
                ?: true

            if (isVerbose) logger.lifecycle("LinRouter: >>> [Plugin Start] 作用于项目: ${target.path}")

            try {
                // 1. SPI 合并逻辑
                pluginManager.withPlugin("com.android.base") {
                    if (isVerbose) logger.lifecycle("LinRouter: [Trace] 检测到 Android 环境 (${target.path})，配置资源合并规则。")
                    extensions.findByType(CommonExtension::class.java)?.apply {
                        packaging {
                            resources {
                                merges.add("META-INF/services/com.lin.router.api.*")
                            }
                        }
                    }
                }

                // 2. KSP 注入
                extensions.findByType(KspExtension::class.java)?.apply {
                    arg("routerModuleName", target.path)
                    if (isVerbose) {
                        arg("routerVerbose", "true")
                        logger.lifecycle("LinRouter: [Trace] 已注入 KSP 参数: routerModuleName=${target.path}")
                    }
                }

                // 3. 智能化聚合判定 (仅 Application 模块)
                pluginManager.withPlugin("com.android.application") {
                    // 默认对所有 Application 模块开启聚合，除非显式通过模块级 extra 属性关闭
                    val isExplicitlyDisabled = target.extensions.extraProperties.has("linRouter.aggregate") &&
                            target.extensions.extraProperties.get("linRouter.aggregate")?.toString()?.toBoolean() == false
                    println("LinRouter: [AutoScan] 模块 ${target.path} 主工程")
                    if (isExplicitlyDisabled) return@withPlugin

                    // 强制要求先评估所有子插件，确保扫描时不漏掉模块
                    rootProject.subprojects.forEach { sub ->
                        if (sub != target) evaluationDependsOn(sub.path)
                    }
                    val currentModule = target.path
                    println("LinRouter: [AutoScan] 模块 ${currentModule} ")
                    afterEvaluate {
                        val kspExtension = extensions.findByType(KspExtension::class.java)
                        if (kspExtension != null) {
                            // 扫描所有应用了本插件的模块 (包括自己)
                            val allRouterModules = rootProject.allprojects
                                .asSequence()
                                .filter { it.path != currentModule && !it.path.trim().equals(":", true) }
                                .filter { subProject ->
                                    // 性能底线：放弃解析 dependencies。
                                    // 强制契约：要求参与路由的子模块必须应用插件，或在 build.gradle 中声明 ext.isRouterModule = true
                                    val hasPlugin = subProject.pluginManager.hasPlugin("com.lin.router.plugin")
                                    val hasExtFlag = subProject.extensions.extraProperties.has("isRouterModule") &&
                                            subProject.extensions.extraProperties.get("isRouterModule").toString().toBoolean()
                                    hasPlugin || hasExtFlag
                                }
                                .map { it.path }
                                .distinct()
                                .joinToString(",")

                            if (allRouterModules.isNotEmpty()) {
                                kspExtension.arg("routerAggregateModules", allRouterModules)
                                println("LinRouter: [AutoScan] 模块 ${target.path} 已自动开启路由聚合，包含模块: $allRouterModules")
                            }
                        }
                    }
                }

                if (isVerbose) logger.lifecycle("LinRouter: <<< [Plugin End] 模块 ${target.path} 处理完毕。")

            } catch (e: Exception) {
                logger.error("LinRouter: [Fatal] 插件初始化失败!", e)
            }
        }
    }

    private fun Project.findLocalProperty(key: String): String? {
        val localPropsFile = rootProject.file("local.properties")
        if (localPropsFile.exists()) {
            val localProps = Properties().apply {
                localPropsFile.inputStream().use { load(it) }
            }
            return localProps.getProperty(key)
        }
        return null
    }
}
