package com.lin.router.plugin

import com.android.build.api.dsl.CommonExtension
import com.google.devtools.ksp.gradle.KspExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import java.util.Properties
import java.util.Collections
import java.util.LinkedHashSet

/**
 * LinRouter 官方插件 (Standalone 版)
 * 内置全轨迹日志监控，支持高效排障，完美兼容全版本 Gradle & AGP
 */
class LinRouterPlugin : Plugin<Project> {

    override fun apply(target: Project) {
        // 1. 被动注册：每个应用了插件的模块在配置时自动向根项目注册自己，消除 evaluationDependsOn 强耦合
        registerModule(target)

        with(target) {
            val isVerbose = findProperty("linRouter.verbose")?.toString()?.toBoolean() 
                ?: findLocalProperty("linRouter.verbose")?.toBoolean() 
                ?: true

            if (isVerbose) logger.lifecycle("LinRouter: >>> [Plugin Start] 作用于项目: ${target.path}")

            try {
                // 2. SPI 合并逻辑：使用反射兼容全版本 AGP (4.x / 7.x / 8.x / 9.x)
                pluginManager.withPlugin("com.android.base") {
                    if (isVerbose) logger.lifecycle("LinRouter: [Trace] 检测到 Android 环境 (${target.path})，配置资源合并规则。")
                    extensions.findByType(CommonExtension::class.java)?.apply {
                        configurePackaging(this)
                    }
                }

                // 3. KSP 注入
                extensions.findByType(KspExtension::class.java)?.apply {
                    arg("routerModuleName", target.path)
                    if (isVerbose) {
                        arg("routerVerbose", "true")
                        logger.lifecycle("LinRouter: [Trace] 已注入 KSP 参数: routerModuleName=${target.path}")
                    }
                }

                // 4. 智能化聚合判定 (仅 Application 模块)
                pluginManager.withPlugin("com.android.application") {
                    val isExplicitlyDisabled = target.extensions.extraProperties.has("linRouter.aggregate") &&
                            target.extensions.extraProperties.get("linRouter.aggregate")?.toString()?.toBoolean() == false
                    println("LinRouter: [AutoScan] 模块 ${target.path} 主工程")
                    if (isExplicitlyDisabled) return@withPlugin

                    val currentModule = target.path
                    println("LinRouter: [AutoScan] 模块 ${currentModule} ")
                    
                    afterEvaluate {
                        val kspExtension = extensions.findByType(KspExtension::class.java) ?: return@afterEvaluate
                        val aggregationMode = target.findProperty("linRouter.aggregationMode")?.toString() ?: "auto"

                        // 极速通道：单工程模式，直接返回
                        if (aggregationMode.equals("single", ignoreCase = true)) {
                            kspExtension.arg("routerAggregateModules", currentModule)
                            println("LinRouter: [FastPath] 命中单体工程模式，聚合目标: $currentModule")
                            return@afterEvaluate
                        }

                        // 优先从共享的被动注册集合中读取已配置的子模块，实现 O(1) 配置及 Configuration Cache 兼容
                        val rootExtra = try { target.rootProject.extensions.extraProperties } catch (t: Throwable) { null }
                        val registeredModules = if (rootExtra != null && rootExtra.has("linRouterModules")) {
                            @Suppress("UNCHECKED_CAST")
                            (rootExtra.get("linRouterModules") as? Set<String>)
                                ?.filter { it != currentModule && !it.trim().equals(":", true) }
                        } else null

                        val aggregateTargets = if (!registeredModules.isNullOrEmpty()) {
                            // 成功走限制解除后的现代化高性能通道
                            registeredModules.joinToString(",")
                        } else {
                            // 回退通道：探测是否启用了 Isolated Projects 限制
                            val isIsolated = target.findProperty("org.gradle.unsafe.isolated-projects")?.toString()?.toBoolean() == true
                            if (isIsolated) {
                                println("LinRouter: [Warning] 开启了 Isolated Projects，无法进行跨项目扫描，仅聚合当前模块。")
                                currentModule
                            } else {
                                // 安全回退：强制要求评估子项目（传统的强耦合方式）
                                try {
                                    rootProject.subprojects.forEach { sub ->
                                        if (sub != target) evaluationDependsOn(sub.path)
                                    }
                                    rootProject.allprojects
                                        .asSequence()
                                        .filter { it.path != currentModule && !it.path.trim().equals(":", true) }
                                        .filter { subProject ->
                                            val hasPlugin = subProject.pluginManager.hasPlugin("com.lin.router.plugin")
                                            val hasExtFlag = subProject.extensions.extraProperties.has("isRouterModule") &&
                                                    subProject.extensions.extraProperties.get("isRouterModule").toString().toBoolean()
                                            hasPlugin || hasExtFlag
                                        }
                                        .map { it.path }
                                        .distinct()
                                        .joinToString(",")
                                } catch (t: Throwable) {
                                    currentModule
                                }
                            }
                        }

                        val finalTargets = aggregateTargets.ifEmpty { currentModule }
                        kspExtension.arg("routerAggregateModules", finalTargets)
                        println("LinRouter: [AutoScan] 模块 ${target.path} 路由配置完毕，聚合列表: $finalTargets")
                    }
                }

                if (isVerbose) logger.lifecycle("LinRouter: <<< [Plugin End] 模块 ${target.path} 处理完毕。")

            } catch (e: Exception) {
                logger.error("LinRouter: [Fatal] 插件初始化失败!", e)
            }
        }
    }

    private fun registerModule(project: Project) {
        try {
            val root = project.rootProject
            val extra = root.extensions.extraProperties
            val list = synchronized(extra) {
                if (extra.has("linRouterModules")) {
                    @Suppress("UNCHECKED_CAST")
                    extra.get("linRouterModules") as? MutableSet<String>
                } else {
                    val newSet = Collections.synchronizedSet(LinkedHashSet<String>())
                    extra.set("linRouterModules", newSet)
                    newSet
                }
            }
            list?.add(project.path)
        } catch (t: Throwable) {
            // 规避 Isolated Projects 模式下的跨项目访问限制
        }
    }

    private fun configurePackaging(extension: Any) {
        try {
            // 1. 优先尝试 AGP 8.0+ 的包装配置 API (packaging.resources.merges)
            val packagingMethod = extension.javaClass.getMethod("getPackaging")
            val packaging = packagingMethod.invoke(extension)
            val resourcesMethod = packaging.javaClass.getMethod("getResources")
            val resources = resourcesMethod.invoke(packaging)
            val getMergesMethod = resources.javaClass.getMethod("getMerges")
            val merges = getMergesMethod.invoke(resources) as? MutableSet<String>
            merges?.add("META-INF/services/com.lin.router.api.*")
        } catch (t: Throwable) {
            try {
                // 2. 回退到 AGP 7.x 及以下的 packagingOptions.resources.merges API
                val packagingOptionsMethod = extension.javaClass.getMethod("getPackagingOptions")
                val packagingOptions = packagingOptionsMethod.invoke(extension)
                val resourcesMethod = packagingOptions.javaClass.getMethod("getResources")
                val resources = resourcesMethod.invoke(packagingOptions)
                val getMergesMethod = resources.javaClass.getMethod("getMerges")
                val merges = getMergesMethod.invoke(resources) as? MutableSet<String>
                merges?.add("META-INF/services/com.lin.router.api.*")
            } catch (ex: Throwable) {
                try {
                    // 3. 极旧版本 AGP 4.x 兼容 (packagingOptions.merges)
                    val packagingOptionsMethod = extension.javaClass.getMethod("getPackagingOptions")
                    val packagingOptions = packagingOptionsMethod.invoke(extension)
                    val mergesMethod = packagingOptions.javaClass.getMethod("getMerges")
                    val merges = mergesMethod.invoke(packagingOptions) as? MutableSet<String>
                    merges?.add("META-INF/services/com.lin.router.api.*")
                } catch (e: Throwable) {
                    // 忽略所有无法配置的异常，确保不影响核心编译
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
