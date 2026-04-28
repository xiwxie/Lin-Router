package com.lin.router.api

import android.app.Activity
import android.content.Intent
import android.os.SystemClock

public object LinRouter {
    private const val TAG = "LinRouter"
    private val routeMap = mutableMapOf<String, Class<*>>()
    private val interceptorMetas = mutableListOf<LinInterceptorMeta>()
    private var isInitialized = false

    // 日志系统
    private var isDebug = false
    private var logger: IRouterLogger = DefaultRouterLogger(false)

    // 参数注入器缓存池
    private val injectorCache = android.util.LruCache<String, LinRouterInjector>(50)

    /**
     * 设置调试模式
     */
    public fun setDebug(debug: Boolean): LinRouter {
        this.isDebug = debug
        // 如果当前是默认实现，则更新其开关
        if (this.logger is DefaultRouterLogger) {
            this.logger = DefaultRouterLogger(debug)
        }
        return this
    }

    /**
     * 设置自定义日志代理
     */
    public fun setLogger(proxy: IRouterLogger): LinRouter {
        this.logger = proxy
        return this
    }

    public fun init() {
        if (isInitialized) return
        val startTime = SystemClock.elapsedRealtime()
        try {
            // 【纯 KSP 聚合方案】
            val hubClass = Class.forName("com.lin.router.generated.LinRouterAppHub")
            val method = hubClass.getMethod("init", MutableMap::class.java, MutableList::class.java)
            method.invoke(null, routeMap, interceptorMetas)

            // 排序拦截器
            interceptorMetas.sortByDescending { it.priority }

            isInitialized = true
            val totalTime = SystemClock.elapsedRealtime() - startTime
            logger.i(TAG, "初始化成功 (KSP 聚合模式)！路由节点数: ${routeMap.size}, 拦截器数: ${interceptorMetas.size}, 耗时：${totalTime}ms")
            
            if (isDebug) {
                routeMap.forEach { (path, clazz) ->
                    logger.v(TAG, "  [加载路由] $path -> ${clazz.name}")
                }
            }
        } catch (e: ClassNotFoundException) {
            logger.e(TAG, "LinRouter 初始化失败：未找到 LinRouterAppHub。请确保在 app 模块应用了 com.lin.router.plugin")
        } catch (e: Exception) {
            logger.e(TAG, "LinRouter 初始化发生异常", e)
        }
    }

    @JvmStatic
    public fun build(path: String): RouteRequest = RouteRequest(path)

    /**
     * 引擎层：执行实例获取 (同步返回)
     */
    public fun executeFetchClass(request: RouteRequest): Class<*>? {
        return routeMap[request.path]
    }

    /**
     * 引擎层：执行实例获取 (同步返回)
     */
    public fun executeFetch(request: RouteRequest): Any? {
        val targetClass = routeMap[request.path] ?: return null
        request.targetClass = targetClass
        return try {
            val instance = targetClass.getDeclaredConstructor().newInstance()

            if (instance is androidx.fragment.app.Fragment && !request.extras.isEmpty) {
                instance.arguments = request.extras
            }

            instance
        } catch (e: Exception) {
            logger.e(TAG, "实例获取失败: ${request.path}", e)
            null
        }
    }


    /**
     * 3. 供内部调用的 Activity 导航逻辑 (包含拦截器责任链)
     */
    public fun executeNavigate(request: RouteRequest, callback: RouteCallback?) {
        if (!isInitialized) logger.w(TAG, "警告：请先调用 LinRouter.init()")

        val targetClass = routeMap[request.path]
        if (targetClass == null) {
            logger.e(TAG, "找不到路由: ${request.path}")
            callback?.onLost(request)
            return
        }
        request.targetClass = targetClass

        // 启动责任链
        val chain = RealRouteChain(interceptorMetas, 0, request, callback) { req ->
            realStartActivity(req, callback)
        }
        chain.proceed(request)
    }

    private fun realStartActivity(request: RouteRequest, callback: RouteCallback?) {
        val context = request.context ?: return
        try {
            val intent = Intent(context, request.targetClass).apply {
                putExtras(request.extras)
                if (request.flags != -1) addFlags(request.flags)
                else if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            if (request.shouldFinishCurrent) {
                if (context is Activity) {
                    context.finish()
                } else {
                    logger.w(TAG, "调用了 withFinish()，但传入的 Context 不是 Activity，无法销毁！")
                }
            }
            if (context is Activity && request.enterAnim != -1 && request.exitAnim != -1) {
                context.overridePendingTransition(request.enterAnim, request.exitAnim)
            }
            callback?.onArrival(request)
        } catch (e: Exception) {
            logger.e(TAG, "Activity 跳转失败: ${request.path}", e)
        }
    }

    /**
     * 5. 自动参数注入入口 (配合 @LinParam)
     */
    public fun inject(target: Any) {
        val className = target.javaClass.name
        val injectorName = "${className}_LinInjector"

        try {
            var injector = injectorCache.get(className)
            if (injector == null) {
                val clazz = Class.forName(injectorName)
                injector = clazz.getDeclaredConstructor().newInstance() as LinRouterInjector
                injectorCache.put(className, injector)
            }
            injector.inject(target)
        } catch (e: ClassNotFoundException) {
            logger.v(TAG, "页面无注入配置: $className")
        } catch (e: Exception) {
            logger.e(TAG, "参数注入失败: $className", e)
        }
    }
}
