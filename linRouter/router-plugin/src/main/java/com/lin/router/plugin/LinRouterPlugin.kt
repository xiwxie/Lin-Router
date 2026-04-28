package com.lin.router.plugin

import com.android.build.api.dsl.CommonExtension
import com.google.devtools.ksp.gradle.KspExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import java.util.Properties

class LinRouterPlugin : Plugin<Project> {

    override fun apply(target: Project) {
        with(target) {
            pluginManager.withPlugin("com.android.base") {
                extensions.findByType(CommonExtension::class.java)?.apply {
                    packaging {
                        resources {
                            // AGP 8.x 的现代化 merges 语法
                            merges.add("META-INF/services/com.lin.router.api.*")
                        }
                    }
                }
            }

            // 1. 获取编译期日志开关 (优先级：命令行 -P > local.properties > 默认false)
            val isVerbose = findProperty("linRouter.verbose")?.toString()?.toBoolean() 
                ?: findLocalProperty("linRouter.verbose")?.toBoolean() 
                ?: false

            // 2. 为当前模块注入配置
            extensions.findByType(KspExtension::class.java)?.apply {
                arg("routerModuleName", target.path)
                if (isVerbose) {
                    arg("routerVerbose", "true")
                }
            }

            // 3. 智能化聚合：如果是壳工程，自动扫描全工程路由模块
            pluginManager.withPlugin("com.android.application") {
                // 强制要求先评估所有子插件，确保扫描时不漏掉模块
                rootProject.subprojects.forEach { sub ->
                    if (sub != target) evaluationDependsOn(sub.path)
                }

                afterEvaluate {
                    val kspExtension = extensions.findByType(KspExtension::class.java)
                    if (kspExtension != null) {
                        // 扫描所有应用了本插件的模块 (包括自己)
                        val allRouterModules = rootProject.allprojects
                            .filter { it.pluginManager.hasPlugin("com.lin.router.plugin") }
                            .map { it.path }
                            .joinToString(",")

                        if (allRouterModules.isNotEmpty()) {
                            kspExtension.arg("routerAggregateModules", allRouterModules)
                            logger.warn("LinRouter: [AutoScan] 发现路由模块列表: $allRouterModules")
                        }
                    }
                }
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
