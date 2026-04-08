package com.lin.router.plugin

import com.android.build.api.dsl.CommonExtension
import com.google.devtools.ksp.gradle.KspExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.dependencies

class LinRouterPlugin : Plugin<Project> {

    override fun apply(target: Project) {
        with(target) {
            // 1. 注册外部 DSL 扩展
            val extension = extensions.create<LinRouterExtension>("linRouter")

            // 2. 自动应用 KSP 插件（严格幂等，防抖安全）
            pluginManager.apply("com.google.devtools.ksp")

            // 3. 智能防冲突：解决 SPI 文件的合并问题
            // 使用 withPlugin 确保只有在 Android 模块下才执行，防止在纯 Java 模块中报错
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

            // 4. KSP 参数注入：使用终极防冲突算法处理项目路径
            extensions.findByType(KspExtension::class.java)?.apply {
                // 将 ":feature:home:ui" 转换为 "FeatureHomeUi"
                val safeModuleName = project.path
                arg("routerModuleName", safeModuleName)
            }

            // 5. 延迟注入云端依赖 (等待 DSL 配置被用户解析完毕后执行)
            afterEvaluate {
                val targetGroupId = extension.groupId.get()
                val targetVersion = extension.version.get()

                dependencies {
                    // 动态拼接 JitPack 上的真实坐标，例如：
                    // implementation("com.github.LinChen.LinRouter:router-api:1.0.0")
                    add("implementation", "$targetGroupId:router-api:$targetVersion")
                    // ksp("com.github.LinChen.LinRouter:minirouter-compiler:1.0.0")
                    add("ksp", "$targetGroupId:router-compiler:$targetVersion")
                }
            }
        }
    }
}