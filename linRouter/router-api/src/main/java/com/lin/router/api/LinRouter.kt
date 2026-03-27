package com.lin.router.api

import android.app.Activity
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import java.util.ServiceLoader

public object LinRouter {
    private const val TAG = "LinRouter"
    private val routeMap = mutableMapOf<String, Class<*>>()
    private val interceptorMetas = mutableListOf<InterceptorMeta>()
    private var isInitialized = false

    // 参数注入器缓存池 (控制反射开销)
    private val injectorCache = android.util.LruCache<String, IRouterInjector>(50)

    public fun init() {
        if (isInitialized) return
        val startTime = SystemClock.elapsedRealtime()
        try {
            // 【核心魔法所在】
            // Debug 时，这里走原生 ServiceLoader 反射加载，耗时极短（仅在开发期）。
            // Release 时，R8 会将其直接编译成类似:
            // Arrays.asList(new AppRouterLoader(), new UserRouterLoader()).forEach(...)

            // 1. 加载所有路由表
            val routerLoaders = ServiceLoader.load(IRouterLoader::class.java)
            for (loader in routerLoaders) {
                loader.loadInto(routeMap)
            }

            // 2. 加载所有拦截器
            val interceptorLoaders = ServiceLoader.load(IInterceptorLoader::class.java)
            for (loader in interceptorLoaders) {
                loader.loadInto(interceptorMetas)
            }
            interceptorMetas.sortByDescending { it.priority }

            isInitialized = true
            val totalTime = SystemClock.elapsedRealtime() - startTime
            Log.i(TAG, "初始化成功！路由节点数: ${routeMap.size}, 拦截器数: ${interceptorMetas.size}, 耗时：$totalTime")
        } catch (e: Exception) {
            Log.e(TAG, "LinRouter 初始化异常", e)
        }
    }

    @JvmStatic
    fun build(path: String): RouteRequest = RouteRequest(path)

    /**
     * 引擎层：执行实例获取 (同步返回)
     */
    fun executeFetchClass(request: RouteRequest): Class<*>? {
        return routeMap[request.path]
    }

    /**
     * 引擎层：执行实例获取 (同步返回)
     */
    fun executeFetch(request: RouteRequest): Any? {
        val targetClass = routeMap[request.path] ?: return null
        request.targetClass = targetClass
        return try {
            val instance = targetClass.getDeclaredConstructor().newInstance()

            // 如果是 Fragment，自动把装配好的 Bundle 塞进去
            if (instance is androidx.fragment.app.Fragment && !request.extras.isEmpty) {
                instance.arguments = request.extras
            }
            // 预留：如果是旧版 android.app.Fragment，也可以在这里加分支

            instance
        } catch (e: Exception) {
            Log.e(TAG, "实例获取失败: ${request.path}", e)
            null
        }
    }


    /**
     * 3. 供内部调用的 Activity 导航逻辑 (包含拦截器责任链)
     */
    fun executeNavigate(request: RouteRequest, callback: RouteCallback?) {
        if (!isInitialized) Log.w(TAG, "请先调用 LinRouter.init()")

        val targetClass = routeMap[request.path]
        if (targetClass == null) {
            Log.e(TAG, "找不到路由: ${request.path}")
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

            if (context is Activity && request.enterAnim != -1 && request.exitAnim != -1) {
                context.overridePendingTransition(request.enterAnim, request.exitAnim)
            }
            callback?.onArrival(request)
        } catch (e: Exception) {
            Log.e(TAG, "Activity 跳转失败: ${request.path}", e)
        }
    }

    /**
     * 5. 自动参数注入入口 (配合 @LinParam)
     */
    fun inject(target: Any) {
        val className = target.javaClass.name
        val injectorName = "${className}_LinInjector"

        try {
            var injector = injectorCache.get(className)
            if (injector == null) {
                val clazz = Class.forName(injectorName)
                injector = clazz.getDeclaredConstructor().newInstance() as IRouterInjector
                injectorCache.put(className, injector)
            }
            injector.inject(target)
        } catch (e: ClassNotFoundException) {
            // 正常防守：如果页面没写 @LinParam，KSP 就不会生成 Injector，忽略即可
            Log.v(TAG, "页面无注入配置: $className")
        } catch (e: Exception) {
            Log.e(TAG, "参数注入失败: $className", e)
        }
    }

}