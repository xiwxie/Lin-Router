package com.lin.router.api

import android.content.Context
import android.os.Bundle
import android.os.Parcelable
import java.io.Serializable

public class RouteRequest(public val path: String) {
    public var context: Context? = null
    public val extras: Bundle = Bundle()
    internal var targetClass: Class<*>? = null

    // 高级跳转配置项
    public var flags: Int = -1
    public var enterAnim: Int = -1
    public var exitAnim: Int = -1

    public fun withContext(ctx: Context): RouteRequest = apply { this.context = ctx }

    // --- 极度舒适的链式参数传递 ---
    public fun withString(key: String, value: String?): RouteRequest =
        apply { extras.putString(key, value) }

    public fun withInt(key: String, value: Int): RouteRequest = apply { extras.putInt(key, value) }
    public fun withBoolean(key: String, value: Boolean): RouteRequest =
        apply { extras.putBoolean(key, value) }

    public fun withLong(key: String, value: Long): RouteRequest =
        apply { extras.putLong(key, value) }

    public fun withDouble(key: String, value: Double): RouteRequest =
        apply { extras.putDouble(key, value) }

    // 支持复杂对象
    public fun withParcelable(key: String, value: Parcelable?): RouteRequest =
        apply { extras.putParcelable(key, value) }

    public fun withSerializable(key: String, value: Serializable?): RouteRequest =
        apply { extras.putSerializable(key, value) }

    public fun withBundle(bundle: Bundle): RouteRequest = apply { extras.putAll(bundle) }

    // --- Activity 专属配置 ---
    public fun withFlags(flags: Int): RouteRequest = apply { this.flags = flags }
    public fun withTransition(enterAnim: Int, exitAnim: Int): RouteRequest = apply {
        this.enterAnim = enterAnim
        this.exitAnim = exitAnim
    }

    /**
     * 出口 1：用于 Activity 跳转 (无返回值，支持责任链与回调)
     */
    public fun navigate(callback: RouteCallback? = null) {
        LinRouter.executeNavigate(this, callback)
    }

    /**
     * 出口 2：用于获取 Fragment 或 接口实例 (泛型 T 智能推导)
     * 使用 reified，编译器会自动推断 T 的真实类型并进行安全转换
     */
    public inline fun <reified T> fetch(): T? {
        val instance = LinRouter.executeFetch(this)
        // 极致的类型安全：如果拿到的对象不是 T 类型，直接返回 null，绝不抛出 ClassCastException
        return instance as? T
    }

    /**
     * 出口 3：用于获取 class
     * 使用 reified，编译器会自动推断 T 的真实类型并进行安全转换
     */
    public inline fun <reified T> fetchClass(): Class<*>? {
        return LinRouter.executeFetchClass(this)
    }

}