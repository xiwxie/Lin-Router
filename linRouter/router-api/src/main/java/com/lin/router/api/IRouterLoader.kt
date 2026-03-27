package com.lin.router.api

public interface IRouterLoader {
    public fun loadInto(map: MutableMap<String, Class<*>>)
}