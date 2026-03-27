package com.lin.router.api

public interface IInterceptorLoader {
    public fun loadInto(list: MutableList<InterceptorMeta>)
}