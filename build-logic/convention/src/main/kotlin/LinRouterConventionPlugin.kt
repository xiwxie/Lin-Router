import com.android.build.api.dsl.CommonExtension
import com.google.devtools.ksp.gradle.KspExtension
import com.misterp.build.plugin.ext.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

class LinRouterConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            // 1. 自动应用 KSP 插件
            pluginManager.apply("com.google.devtools.ksp")

            // 2. 自动解决 SPI 文件冲突 (智能拦截 Android Application 和 Library)
            extensions.findByType(CommonExtension::class.java)?.apply {
                packaging {
                    resources {
                        // 使用通配符，一次性解决路由和拦截器的 SPI 合并
                        merges.add("META-INF/services/com.lin.router.api.*")
//                        merge("META-INF/services/com.lin.router.api.*")
                    }
                }
            }

            // 3. 自动为 KSP 注入当前模块的名称
            extensions.configure<KspExtension> {
                arg("moduleName", project.path)
            }

            // 4. 自动添加依赖，业务模块再也不用手写 implementation 和 ksp 了
            dependencies {
                // 根据你在 TOML 里定义的别名查找依赖（这里假设别名为 minirouter-api 和 minirouter-compiler）
                val apiDependency = libs.findLibrary("lin-router-api").get()
                val compilerDependency = libs.findLibrary("lin-router-ksp").get()

                // 添加远程依赖
                add("implementation", apiDependency)
                add("ksp", compilerDependency)
            }
        }
    }
}