package com.lin.router.api

import android.app.Activity
import android.content.Intent
import android.os.SystemClock

public object LinRouter {
    private const val TAG = "LinRouter"
    
    private var routeMap: MutableMap<String, Class<*>> = mutableMapOf()
    private var interceptorMetas: MutableList<LinInterceptorMeta> = mutableListOf()
    private var isInitialized = false

    // 日志系统重构
    private var currentLogLevel: LinLogLevel = LinLogLevel.INFO
    private var logger: IRouterLogger = DefaultRouterLogger(LinLogLevel.INFO)

    // 参数注入器缓存池
    private val injectorCache = android.util.LruCache<String, LinRouterInjector>(50)

    /**
     * 设置日志级别
     */
    public fun setLogLevel(level: LinLogLevel): LinRouter {
        this.currentLogLevel = level
        val l = this.logger
        if (l is DefaultRouterLogger) {
            l.setLevel(level)
        }
        return this
    }

    /**
     * 旧接口兼容：设置是否开启调试日志
     */
    @Deprecated("请使用 setLogLevel(LinLogLevel)", ReplaceWith("setLogLevel(if(debug) LinLogLevel.VERBOSE else LinLogLevel.INFO)"))
    public fun setDebug(debug: Boolean): LinRouter {
        return setLogLevel(if (debug) LinLogLevel.VERBOSE else LinLogLevel.INFO)
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
            // 1. 加载聚合中枢
            val hub = Class.forName("com.lin.router.generated.LinRouterAppHub")
                .getDeclaredConstructor()
                .newInstance() as IRouterAppHub
            
            // 2. 精准分配内存
            val rSize = hub.getRouteCount()
            val iSize = hub.getInterceptorCount()
            
            if (rSize > 0) {
                val cap = (rSize / 0.75f + 1f).toInt()
                routeMap = java.util.HashMap(cap)
            }
            if (iSize > 0) {
                interceptorMetas = java.util.ArrayList(iSize)
            }

            // 3. 全静态强引用注入
            hub.init(routeMap, interceptorMetas)

            // 4. 排序拦截器
            interceptorMetas.sortByDescending { it.priority }

            isInitialized = true
            val totalTime = SystemClock.elapsedRealtime() - startTime
            
            // 关键初始化信息使用 INFO 级别
            logger.i(TAG, "LinRouter 初始化成功！路由: ${routeMap.size}, 拦截器: ${interceptorMetas.size}, 耗时：${totalTime}ms")
            
            // 路由详情使用 VERBOSE 级别，仅在开发者需要时打印
            routeMap.forEach { (path, clazz) ->
                logger.v(TAG, "  [Route] $path -> ${clazz.name}")
            }
            
        } catch (e: Exception) {
            logger.e(TAG, "LinRouter 初始化严重异常", e)
        }
    }

    @JvmStatic
    public fun build(path: String): RouteRequest = RouteRequest(path)

    public fun executeFetchClass(request: RouteRequest): Class<*>? = routeMap[request.path]

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

    public fun executeNavigate(request: RouteRequest, callback: RouteCallback?) {
        if (!isInitialized) logger.w(TAG, "警告：请先调用 LinRouter.init()")
        val targetClass = routeMap[request.path]
        if (targetClass == null) {
            logger.e(TAG, "找不到路由: ${request.path}")
            callback?.onLost(request)
            return
        }
        request.targetClass = targetClass
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
            if (request.shouldFinishCurrent && context is Activity) context.finish()
            if (context is Activity && request.enterAnim != -1 && request.exitAnim != -1) {
                context.overridePendingTransition(request.enterAnim, request.exitAnim)
            }
            callback?.onArrival(request)
        } catch (e: Exception) {
            logger.e(TAG, "Activity 跳转失败: ${request.path}", e)
        }
    }

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
