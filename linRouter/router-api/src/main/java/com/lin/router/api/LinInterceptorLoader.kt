package com.lin.router.api

/**
 * 拦截器加载器接口
 */
public interface LinInterceptorLoader {
    /**
     * 将当前模块的拦截器加载到全局 List 中
     */
    public fun loadInto(list: MutableList<LinInterceptorMeta>)

    /**
     * 获取当前模块定义的拦截器总数
     */
    public fun getCheckCount(): Int
}
