package com.lin.router.api

/**
 * 路由聚合中枢契约接口
 */
public interface IRouterAppHub {
    /**
     * 获取路由节点总数，用于精准初始化 Map 容量
     */
    public fun getRouteCount(): Int = 0

    /**
     * 获取拦截器总数
     */
    public fun getInterceptorCount(): Int = 0

    /**
     * 执行静态装载
     */
    public fun init(routeMap: MutableMap<String, Class<*>>, interceptors: MutableList<LinInterceptorMeta>)
}
