package com.lin.router.api

import android.content.Context
import android.os.Bundle
import android.os.Parcelable
import java.io.Serializable

public class RouteRequest(val path: String) {
    var context: Context? = null
    val extras: Bundle = Bundle()
    internal var targetClass: Class<*>? = null

    // 高级跳转配置项
    var flags: Int = -1
    var enterAnim: Int = -1
    var exitAnim: Int = -1

    fun withContext(ctx: Context) = apply { this.context = ctx }

    // --- 极度舒适的链式参数传递 ---
    fun withString(key: String, value: String?) = apply { extras.putString(key, value) }
    fun withInt(key: String, value: Int) = apply { extras.putInt(key, value) }
    fun withBoolean(key: String, value: Boolean) = apply { extras.putBoolean(key, value) }
    fun withLong(key: String, value: Long) = apply { extras.putLong(key, value) }
    fun withDouble(key: String, value: Double) = apply { extras.putDouble(key, value) }

    // 支持复杂对象
    fun withParcelable(key: String, value: Parcelable?) = apply { extras.putParcelable(key, value) }
    fun withSerializable(key: String, value: Serializable?) = apply { extras.putSerializable(key, value) }
    fun withBundle(bundle: Bundle) = apply { extras.putAll(bundle) }

    // --- Activity 专属配置 ---
    fun withFlags(flags: Int) = apply { this.flags = flags }
    fun withTransition(enterAnim: Int, exitAnim: Int) = apply {
        this.enterAnim = enterAnim
        this.exitAnim = exitAnim
    }

    /**
     * 出口 1：用于 Activity 跳转 (无返回值，支持责任链与回调)
     */
    fun navigate(callback: RouteCallback? = null) {
        LinRouter.executeNavigate(this, callback)
    }

    /**
     * 出口 2：用于获取 Fragment 或 接口实例 (泛型 T 智能推导)
     * 使用 reified，编译器会自动推断 T 的真实类型并进行安全转换
     */
    inline fun <reified T> fetch(): T? {
        val instance = LinRouter.executeFetch(this)
        // 极致的类型安全：如果拿到的对象不是 T 类型，直接返回 null，绝不抛出 ClassCastException
        return instance as? T
    }

    /**
     * 出口 3：用于获取 class
     * 使用 reified，编译器会自动推断 T 的真实类型并进行安全转换
     */
    inline fun <reified T> fetchClass(): Class<*>? {
        return LinRouter.executeFetchClass(this)
    }

}