# 🚀 LinRouter (现代化组件化路由框架)

[![JitPack](https://jitpack.io/v/xiwxie/Lin-Router.svg)](https://jitpack.io/#xiwxie/Lin-Router)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](https://opensource.org/licenses/MIT)

LinRouter 是一个专为现代化 Android 多模块工程打造的**极速、类型安全、编译期聚合**的路由框架。

## 1.1.3 新特性：独立汇总 (Isolated Hub)

新增可选的高性能构建模式，专为 KSP2 设计：

```properties
linRouter.hubMode=isolated
linRouter.aggregationMode=auto
```

修改普通业务代码时，业务 KSP 不再承担 AppHub 的全源码聚合依赖，大幅缩短增量编译耗时。默认保持 `legacy` 兼容旧版行为，可随时回退。无需改变路由调用 API；业务模块仍需显式应用 KSP 并声明 `implementation(router-api)` 与 `ksp(router-compiler)` 依赖。

升级边界、装载时序、R8 混淆规则及实测结果详见 [1.1.3 发布说明](docs/releases/1.1.3.md)。

## ✨ 核心特性

* **编译期路由表：** 通过 `LinRouterAppHub` 静态装载模块 Loader；初始化时通过固定类名反射创建 Hub，需要保留对应 R8 规则。
* **增量构建优化：** KSP 生成模块 Loader，可显式启用独立 Hub 汇总任务，彻底隔离业务代码变动对聚合的影响。
* 🛡️ **类型安全获取:** 利用 Kotlin `reified` 魔法实现 `fetch<T>()`，智能推导目标类型（Fragment/Service），彻底告别 `as` 强转风险。
* 💉 **参数自动注入:** 使用 `@LinParam` 注解，通过生成的 Injector 实现 Intent/Arguments 参数自动装配。
* 🚦 **异步责任链拦截器:** 优雅的 `RouteInterceptor` 引擎，轻松应对登录拦截、埋点监控等异步业务逻辑。
* 📦 **自动化工程集成:** `LinRouterPlugin` 自动识别子模块路由并完成全局聚合，开发者无需手动维护路由表列表。

---

## 📦 极速接入指南

### 1. 配置仓库
在根目录 `settings.gradle.kts` 中添加：

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

### 2. 引入组件

在已有 Android/Kotlin/KSP 版本配置的工程中，通过 `gradle/libs.versions.toml` 统一管理版本：

```toml
[versions]
linRoute = "v1.1.3"

[libraries]
lin-route-plugin = { module = "com.github.xiwxie.Lin-Router:router-plugin", version.ref = "linRoute" }
lin-route-api = { module = "com.github.xiwxie.Lin-Router:router-api", version.ref = "linRoute" }
lin-route-compiler = { module = "com.github.xiwxie.Lin-Router:router-compiler", version.ref = "linRoute" }
```

根 `build.gradle.kts`：

```kotlin
buildscript {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
    dependencies {
        classpath(libs.lin.route.plugin)
    }
}
```

`gradle.properties`（独立模式需使用包含此能力的版本）：

```properties
linRouter.hubMode=isolated
# auto 会聚合当前变体的依赖模块；single 仅汇总 App 本地 Loader。
linRouter.aggregationMode=auto
```

### 3. 业务模块启用

在每个需要生成路由的模块（App 或 Library）中应用插件并声明依赖：

```kotlin
plugins {
    id("com.android.application") // Library 使用 com.android.library
    id("com.google.devtools.ksp") // 版本与项目 Kotlin/KSP 对齐
    id("com.lin.router.plugin")
}

dependencies {
    implementation(libs.lin.route.api)
    ksp(libs.lin.route.compiler)
}
```

---

## 📖 使用文档

### 1. 初始化
在 `Application` 中执行，建议在 `super.onCreate()` 之后立即调用：

```kotlin
class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        
        // 可选：配置日志级别 (默认为 INFO)
        LinRouter.setLogLevel(LinLogLevel.VERBOSE)
        
        // 核心初始化：瞬间加载聚合路由表
        LinRouter.init() 
    }
}
```

### 2. 注册路由
支持 Activity、Fragment 及任何提供无参构造函数的类（作为 Service）：

```kotlin
@LinRoute(path = "/shop/detail")
class ShopDetailActivity : AppCompatActivity() { ... }

@LinRoute(path = "/wallet/panel")
class WithdrawFragment : Fragment() { ... }
```

### 3. 路由跳转 (Activity)
支持极致流畅的链式调用，新增 `withFinish()` 支持：

```kotlin
LinRouter.build("/shop/detail")
    .withContext(this)
    .withString("goodsId", "1024")
    .withFinish() // 跳转成功后自动 finish 当前 Activity
    .withTransition(R.anim.slide_in, R.anim.slide_out)
    .navigate()
```

### 4. 获取实例 (Fragment / Service)
```kotlin
// 自动推断类型，安全获取 Fragment
val fragment = LinRouter.build("/wallet/panel")
    .withDouble("balance", 99.9)
    .fetch<WithdrawFragment>()

// 跨模块获取服务接口
val homeService = LinRouter.build("/service/home").fetch<IHomeService>()
```

### 5. 参数自动注入 (@LinParam)
```kotlin
@LinRoute(path = "/user/profile")
class UserProfileActivity : AppCompatActivity() {

    @LinParam("user_id") // 指定 Key
    lateinit var userId: String

    @LinParam // 默认使用变量名 "age"
    var age: Int = 18

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 自动注入参数，替代繁琐的 intent.getStringExtra
        LinRouter.inject(this) 
    }
}
```

### 6. 全异步拦截器 (@LinInterceptor)
通过 `priority` 指定优先级（值越大越先执行）：

```kotlin
@LinInterceptor(priority = 10)
class AuthInterceptor : RouteInterceptor {
    override fun intercept(chain: RouteInterceptor.Chain) {
        val request = chain.request
        if (request.path.startsWith("/admin/") && !isLogin()) {
            chain.interrupt("Auth Required") // 拦截并中断
            LinRouter.build("/login").withContext(request.context!!).navigate()
        } else {
            chain.proceed(request) // 放行
        }
    }
}
```

### 7. 日志管控
LinRouter 内置了完善的日志监控，支持自定义输出代理：

```kotlin
LinRouter.setLogger(object : IRouterLogger {
    override fun v(tag: String, msg: String) { /* 接入自研日志系统 */ }
    override fun i(tag: String, msg: String) { ... }
    // ...
})
```

---

## 🛠 技术细节
* **编译期聚合**: `LinRouterPlugin` 会自动扫描项目中应用了该插件的子模块，并将模块列表注入到 `app` 模块的 KSP 处理器中，最终生成 `LinRouterAppHub` 字节码。
* **零反射初始化**: `LinRouter.init() ` 内部通过硬编码方式直接调用生成的 `hub.init(routeMap, interceptorMetas)`，完美避开 `Class.forName` 以外的反射操作。

