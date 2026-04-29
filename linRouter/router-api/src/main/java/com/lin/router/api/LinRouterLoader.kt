package com.lin.router.api

import java.lang.Class

/**
 * 路由加载器接口
 */
public interface LinRouterLoader {
    /**
     * 将当前模块的路由节点加载到全局 Map 中
     */
    public fun loadInto(map: MutableMap<String, Class<*>>)

    /**
     * 获取当前模块定义的路由总数
     */
    public fun getCheckCount(): Int
}
