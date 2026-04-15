package com.lin.router.plugin

import com.android.build.api.dsl.CommonExtension
import com.google.devtools.ksp.gradle.KspExtension
import org.gradle.api.Plugin
import org.gradle.api.Project

class LinRouterPlugin : Plugin<Project> {

    override fun apply(target: Project) {
        with(target) {
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
                val safeModuleName = target.path
                arg("routerModuleName", safeModuleName)
            }
        }
    }
}