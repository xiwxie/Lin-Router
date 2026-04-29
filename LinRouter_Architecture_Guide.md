# LinRouter 开发与集成指南

LinRouter 是一款专为组件化架构设计的轻量级、高性能路由框架。它通过 KSP 实现编译期处理，并深度适配了 Kotlin 协程以及现代化 Android 开发标准。

---

## 一、 快速接入

### 1. 根项目配置
在项目根目录的 `build.gradle.kts` 中配置 KSP 插件：
```kotlin
plugins {
    id("com.google.devtools.ksp") version "x.y.z" apply false
}
```

### 2. 模块配置
在每个需要使用路由的模块中应用插件：
```kotlin
plugins {
    id("com.android.library") 
    id("com.google.devtools.ksp")
    id("com.lin.router.plugin") // 核心：自动注入混淆规则与资源合并策略
}

dependencies {
    implementation(project(":linRouter:router-api"))
    ksp(project(":linRouter:router-compiler"))
}
```

---

## 二、 核心用法 (略，保持原有协程与参数注入说明)
...

---

## 三、 极致性能：静态聚合模式 (Scheme A)

LinRouter 默认采用 **“KSP 静态聚合”** 架构，旨在彻底消灭 Android 路由常见的反射损耗。

### 1. 运行机制
*   **编译期**：各子模块生成局部 `Loader`。App 模块的 KSP 插件通过 Gradle 隧道收集这些 `Loader`，生成一个名为 `LinRouterAppHub` 的总索引类。
*   **运行期**：`LinRouter.init()` 优先加载该索引类。通过硬编码直接 `new` 实例，不产生任何 `ServiceLoader` 扫描开销。

### 2. 0 反射参数注入
通过 `LinRouter.inject(this)` 注入参数时，框架会直接从预加载的静态 Map 中获取 Injector 实例。
*   **传统路由**：`Class.forName("Xxx_Injector")` -> 反射创建 -> 耗时大、R8 难优化。
*   **LinRouter**：`Map.get("ClassName")` -> 静态实例调用 -> **0 反射损耗**。

### 3. R8/Proguard 深度协同
由于 `LinRouterAppHub` 在源码层面直接引用了所有的 Loader 和实现类，R8 能够完美追踪引用链：
*   **不被误删**：无需编写复杂的 Keep 规则。
*   **内联优化**：R8 会自动将简单的 new 操作内联，启动速度接近原生调用。

---

## 四、 混淆配置 (Proguard/R8)

```proguard
# 保护生成的路由表加载类
-keep class com.lin.router.generated.** { *; }

# 保护 Loader 接口实现类的类名，确保 R8 优化路径正确
-keepnames class * implements com.lin.router.api.LinRouterLoader
-keepnames class * implements com.lin.router.api.LinInterceptorLoader

# 保护参数注入器的方法签名
-keepclassmembers class * implements com.lin.router.api.LinRouterInjector {
    public void inject(java.lang.Object);
}
```
