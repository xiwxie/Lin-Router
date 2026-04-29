package com.lin.router.api

import android.util.Log

/**
 * 日志级别定义
 */
public enum class LinLogLevel(public val value: Int) {
    ALL(0),
    VERBOSE(2),
    DEBUG(3),
    INFO(4),
    WARN(5),
    ERROR(6),
    OFF(100) // 全局关闭
}

public interface IRouterLogger {
    public fun v(tag: String, msg: String)
    public fun d(tag: String, msg: String)
    public fun i(tag: String, msg: String)
    public fun w(tag: String, msg: String)
    public fun e(tag: String, msg: String)
    public fun e(tag: String, msg: String, e: Throwable)
}

/**
 * 默认日志实现，支持级别过滤
 */
internal class DefaultRouterLogger(private var level: LinLogLevel) : IRouterLogger {

    fun setLevel(level: LinLogLevel) {
        this.level = level
    }

    override fun v(tag: String, msg: String) {
        if (level.value <= LinLogLevel.VERBOSE.value) Log.v(tag, msg)
    }

    override fun d(tag: String, msg: String) {
        if (level.value <= LinLogLevel.DEBUG.value) Log.d(tag, msg)
    }

    override fun i(tag: String, msg: String) {
        if (level.value <= LinLogLevel.INFO.value) Log.i(tag, msg)
    }

    override fun w(tag: String, msg: String) {
        if (level.value <= LinLogLevel.WARN.value) Log.w(tag, msg)
    }

    override fun e(tag: String, msg: String) {
        if (level.value <= LinLogLevel.ERROR.value) Log.e(tag, msg)
    }

    override fun e(tag: String, msg: String, e: Throwable) {
        if (level.value <= LinLogLevel.ERROR.value) Log.e(tag, msg, e)
    }
}
