import com.android.build.api.dsl.CommonExtension
import com.google.devtools.ksp.gradle.KspExtension
import com.misterp.plugin.ext.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import java.util.Properties

/**
 * LinRouter 增强版插件：包含全生命周期日志与异常捕获
 */
class LinRouterConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            try {
                // 1. 自动应用必要插件
                pluginManager.apply("com.lin.router.plugin")
                pluginManager.apply("com.google.devtools.ksp")
                
                // 2. 自动解决 SPI 文件冲突
                extensions.findByType(CommonExtension::class.java)?.apply {
                    packaging {
                        resources {
                            merges.add("META-INF/services/com.lin.router.api.*")
                        }
                    }
                }

                // 3. 自动为 KSP 注入配置 (默认开启详细日志)
                val isVerbose = findProperty("linRouter.verbose")?.toString()?.toBoolean() ?: true
                extensions.configure<KspExtension> {
                    arg("routerModuleName", project.path)
                    if (isVerbose) {
                        arg("routerVerbose", "true")
                    }
                }

                // 4. 智能化聚合判定逻辑 (增加异常防护)
                pluginManager.withPlugin("com.android.application") {
                    val isExplicitlyDisabled = target.extensions.extraProperties.has("linRouter.aggregate") &&
                        target.extensions.extraProperties.get("linRouter.aggregate")?.toString()?.toBoolean() == false
                    
                    if (isExplicitlyDisabled) {
                        if (isVerbose) logger.lifecycle("LinRouter: [Plugin] 模块 ${target.path} 已显式禁用聚合。")
                        return@withPlugin
                    }

                    // 预评估子模块，排除根模块和空路径
                    rootProject.subprojects.forEach { sub ->
                        if (sub.path.trim() != ":" && sub != target) {
                            try {
                                evaluationDependsOn(sub.path)
                            } catch (e: Exception) {
                                // 捕获循环依赖或评估失败，但不阻塞主流程
                                if (isVerbose) logger.warn("LinRouter: [Plugin] 预评估模块 ${sub.path} 跳过: ${e.message}")
                            }
                        }
                    }

                    afterEvaluate {
                        try {
                            val kspExtension = extensions.findByType(KspExtension::class.java)
                            if (kspExtension == null) {
                                logger.error("LinRouter: [Plugin] 错误：在 Application 模块中未发现 KSP 扩展，聚合中断。")
                                return@afterEvaluate
                            }

                            // 扫描所有应用了路由插件的模块
                            val allRouterModules = rootProject.allprojects
                                .filter { 
                                    it.path.trim() != ":" && (
                                        it.pluginManager.hasPlugin("com.lin.router.plugin") || 
                                        it.pluginManager.hasPlugin("nowinandroid.linRouter")
                                    )
                                }
                                .map { it.path }
                                .distinct()

                            if (allRouterModules.isNotEmpty()) {
                                val argValue = allRouterModules.joinToString(",")
                                kspExtension.arg("routerAggregateModules", argValue)
                                logger.lifecycle("LinRouter: [Plugin] 聚合成功！目标: ${target.path}, 包含模块: $argValue")
                            } else {
                                if (isVerbose) logger.warn("LinRouter: [Plugin] 聚合列表为空，请确认子模块是否已应用 linRouter 插件。")
                            }
                        } catch (e: Exception) {
                            logger.error("LinRouter: [Plugin] afterEvaluate 执行聚合逻辑时崩溃！", e)
                        }
                    }
                }

                // 5. 自动添加核心依赖
                dependencies {
                    val apiDependency = libs.findLibrary("lin-route-api").get()
                    val compilerDependency = libs.findLibrary("lin-route-compiler").get()
                    add("implementation", apiDependency)
                    add("ksp", compilerDependency)
                }            } catch (e: Exception) {
                logger.error("LinRouter: [Plugin] 插件应用阶段发生严重异常！", e)
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
