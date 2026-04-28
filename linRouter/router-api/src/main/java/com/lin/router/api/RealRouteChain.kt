package com.lin.router.api

import android.os.Handler
import android.os.Looper
import android.util.Log

public class RealRouteChain(
    private val interceptors: List<LinInterceptorMeta>,
    private var index: Int,
    override val request: RouteRequest,
    private val callback: RouteCallback?,
    private val onChainCompleted: (RouteRequest) -> Unit
) : RouteInterceptor.Chain {
    private val mainHandler = Handler(Looper.getMainLooper())

    // ⚠️ 核心状态锁：防止拦截器内部多次调用 proceed 或 interrupt
    private var isHandled = false

    override fun proceed(request: RouteRequest) {
        if (isHandled) {
            Log.e("LinRouter", "【拦截器异常】责任链已流转，严禁在同一个 Interceptor 中多次调用 proceed 或 interrupt！")
            return
        }
        isHandled = true

        if (index >= interceptors.size) {
            mainHandler.post { onChainCompleted(request) }
            return
        }

        val nextMeta = interceptors[index]
        // 索引 +1，交给下一个拦截器
        val nextChain = RealRouteChain(interceptors, index + 1, request, callback, onChainCompleted)
        nextMeta.interceptor.intercept(nextChain)
    }

    override fun interrupt(reason: String) {
        if (isHandled) {
            Log.e("LinRouter", "【拦截器异常】责任链已流转，严禁在同一个 Interceptor 中多次调用 proceed 或 interrupt！")
            return
        }
        isHandled = true

        // ⚠️ 核心兜底：哪怕业务方没有传 Callback 监听，框架也必须在控制台大声喊出来！
        Log.w("LinRouter", "🚫 路由跳转被拦截中断！\n -> 目标路径: [${request.path}]\n -> 中断原因: $reason")

        mainHandler.post { callback?.onInterrupt(request, reason) }
    }
}