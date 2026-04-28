package com.lin.router.api

public interface LinRouterLoader {
    public fun loadInto(map: MutableMap<String, Class<*>>)
}