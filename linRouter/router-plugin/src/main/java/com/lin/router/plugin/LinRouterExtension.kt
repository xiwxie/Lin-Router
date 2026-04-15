// minirouter-plugin/src/main/kotlin/com/lin/router/plugin/LinRouterExtension.kt
package com.lin.router.plugin

import org.gradle.api.provider.Property

/**
 * 提供给外部业务模块的 DSL 配置块
 */
abstract class LinRouterExtension {
    
    // 允许用户自定义拉取底层依赖的 GroupId
    abstract val groupId: Property<String>
    
    // 允许用户自定义底层组件的版本号
    abstract val version: Property<String>

    init {
        // 💡 默认值：直接指向你在 JitPack 上的开源坐标！
        // 假设你的 GitHub 账号叫 LinChen，仓库名是 LinRouter
        groupId.convention("com.github.xiwxie.Lin-Router")
        
        // 默认底层引擎版本，当你要发新版路由时，在这里改一下默认值即可
        version.convention("v1.0.7")
    }
}