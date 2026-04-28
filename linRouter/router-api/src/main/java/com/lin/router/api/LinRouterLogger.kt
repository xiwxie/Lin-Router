package com.lin.router.api

/**
 * 路由日志接口，支持外部代理
 */
public interface IRouterLogger {
    public fun i(tag: String, message: String)
    public fun w(tag: String, message: String)
    public fun e(tag: String, message: String, throwable: Throwable? = null)
    public fun v(tag: String, message: String)
}

/**
 * 默认 Android Log 实现
 */
internal class DefaultRouterLogger(private var isDebug: Boolean) : IRouterLogger {
    override fun i(tag: String, message: String) {
        if (isDebug) android.util.Log.i(tag, message)
    }

    override fun w(tag: String, message: String) {
        if (isDebug) android.util.Log.w(tag, message)
    }

    override fun e(tag: String, message: String, throwable: Throwable?) {
        if (isDebug) android.util.Log.e(tag, message, throwable)
    }

    override fun v(tag: String, message: String) {
        if (isDebug) android.util.Log.v(tag, message)
    }
}
