# LinRouter 修改记录 (2026-04-28)

## 核心 API 重构 (router-api)
- **命名规范化**：所有核心接口增加 `Lin` 前缀，提升辨识度。
    - `IRouterLoader` -> `LinRouterLoader`
    - `IRouterInjector` -> `LinRouterInjector`
    - `IInterceptorLoader` -> `LinInterceptorLoader`
- **拦截器升级**：
    - 废弃 `Interceptor.kt`，新增 `LinInterceptor.kt`。
    - 引入 `LinInterceptorMeta.kt` 管理拦截器元数据。
- **日志系统**：新增 `LinRouterLogger` 统一日志输出。
- **路由逻辑**：优化 `LinRouter.kt` 初始化逻辑，支持更高效的自动注册检索。

## 注解处理器优化 (router-compiler)
- **RouteProcessor**：
    - 优化了 Kotlin Poat 代码生成逻辑。
    - 增强了对多模块环境下路由表冲突的检测与处理。

## Gradle 插件升级 (router-plugin)
- **自动化注册**：在 `LinRouterPlugin` 中集成 ASM 字节码操作，实现在编译期自动扫描并注入路由表及拦截器，彻底告别手动初始化。
- **配置优化**：优化插件依赖管理及编译触发逻辑。

## 新增功能与模块
- **Module-A**：新增演示用库模块，验证多模块路由跳转能力。
- **示例工程**：在 `app` 模块中新增 `RouterInterceptor` 示例及 `HomeService` 服务调用示例。

## 工程基建
- **版本管理**：更新 `libs.versions.toml`，规范依赖版本。
- **构建逻辑**：重构 `build-logic` 下的 Convention 插件，统一编译配置。
- **环境配置**：更新 `.gitignore` 过滤规则，新增架构指南文档 `LinRouter_Architecture_Guide.md`。
