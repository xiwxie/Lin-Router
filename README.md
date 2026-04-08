# 🚀 LinRouter (组件化路由框架)

[![JitPack](https://jitpack.io/#xiwxie/Lin-Router.svg)](https://jitpack.io/#LinChen/LinRouter)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](https://opensource.org/licenses/MIT)

LinRouter 是一个专为现代化 Android 多模块工程打造的**极速、类型安全、零反射**的组件化路由框架。

彻底抛弃沉重的 ASM 字节码插桩，拥抱 Google 官方工具链。基于 `KSP + Java SPI + R8 优化` 架构，在提供全能路由能力的同时，

## ✨ 核心特性

* ⚡️ **极致冷启动 :** 充分利用 R8 `ServiceLoaderRewriter` 机制，在 Release 包中自动完成代码硬编码替换，运行时**零反射、零全量扫描**。
* 🚀 **极速编译体验:** 纯 KSP + SPI 架构，无任何 ASM / Transform 字节码全局插桩逻辑，完美支持 Gradle 增量编译与配置缓存。
* 🛡️ **极致类型安全:** 提供支持 `reified T` 的 `fetch<T>()` API，智能推导目标类型（如 Fragment、服务接口），彻底告别 `as` 强转异常。
* 💉 **可插拔参数注入:** 内置 `@LinParam` 注解，仅通过 KSP 生成辅助类即可实现页面参数自动装配，无需手写繁琐的 `getExtra`。
* 🚦 **全异步责任链拦截器:** 优雅的 `Interceptor` 引擎，轻松应对复杂的路由拦截、异步网络鉴权与登录状态打断。
* 📦 **极简傻瓜式接入:** 提供高度封装的 Gradle Plugin，一键应用路由基建，自动解决 SPI 资源合并冲突。

---

## 📦 极速接入指南

### 1. 添加 JitPack 仓库
在项目根目录的 `settings.gradle.kts` 中配置：

```kotlin
pluginManagement {
    repositories {
        gradlePluginPortal()
        maven { url = uri("[https://jitpack.io](https://jitpack.io)") }
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("[https://jitpack.io](https://jitpack.io)") }
    }
}
```
### 2. 引入 Gradle 插件
在项目根目录的 build.gradle.kts 中引入插件声明：

```kotlin
plugins {
    // 替换为你的真实 GitHub 账号和仓库版本
    id("com.lin.router.plugin") version "1.0.0" apply false 
}
```
### 3. 在业务模块中启用
在任意需要使用路由的子模块（如 app、module-shop）中，只需一行代码即可瞬间赋能：

```kotlin
plugins {
    id("com.android.library")
    id("com.lin.router.plugin") // 👈 仅需一行，自动配置 KSP、依赖与防冲突打包
}
```
## 📖 使用文档
### 1. 全局初始化
在宿主 App 的 Application 中执行初始化：

```kotlin
class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        LinRouter.init() // 瞬间完成，无冷启动负担
    }
}
```
### 2. 注册页面路由
支持对 Activity 和 Fragment 进行路由注册：

```kotlin
@LinRoute(path = "/shop/detail")
class ShopDetailActivity : AppCompatActivity() { ... }

@LinRoute(path = "/wallet/panel")
class WithdrawFragment : Fragment() { ... }

```
### 3. 发起纯净跳转 (Activity)
支持极致流畅的链式调用，可配置转场动画、启动模式等：

```kotlin
LinRouter.build("/shop/detail")
    .withContext(this)
    .withString("goodsId", "10086")
    .withFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
    .withTransition(R.anim.slide_in, R.anim.slide_out)
    .navigate()
```
### 4. 跨模块获取实例 (Fragment / 接口服务)
利用 Kotlin 泛型的终极魔法，无损获取目标对象，绝对类型安全：

```kotlin
// 智能推导为 WithdrawFragment，无强转风险
val fragment = LinRouter.build("/wallet/panel")
    .withDouble("balance", 999.9)
    .fetch<WithdrawFragment>()
```
### 5. 黑科技：参数自动注入 (@LinParam)
告别满屏的样板代码，一键提取 Intent/Arguments 参数：
```kotlin
@LinRoute(path = "/user/profile")
class UserProfileActivity : AppCompatActivity() {

    @LinParam // 默认使用变量名 "userId" 作为 Key
    lateinit var userId: String

    @LinParam("user_age") // 手动指定 Key
    var age: Int = 18 // 支持默认值保底

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        LinRouter.inject(this) // 👈 一行代码，所有 @LinParam 变量自动赋值！
        
        Log.d("Router", "User: $userId, Age: $age")
    }
}
```
### 6. 全异步拦截器 (@LinInterceptor)
按优先级（priority 越大越先执行）拦截跨模块跳转：

```kotlin
@LinInterceptor(priority = 100)
class LoginInterceptor : RouteInterceptor {
    override fun intercept(chain: RouteInterceptor.Chain) {
        val request = chain.request
        
        if (request.path.startsWith("/wallet/") && !isLogin()) {
            // 拦截跳转
            chain.interrupt("尚未登录，已拦截") 
            // 唤起登录页
            LinRouter.build("/user/login").withContext(request.context!!).navigate()
        } else {
            // 放行
            chain.proceed(request)
        }
    }
}
```