package com.lin.router.api

public interface RouteInterceptor {
    public fun intercept(chain: Chain)
    public interface Chain {
        public val request: RouteRequest
        public fun proceed(request: RouteRequest)
        public fun interrupt(reason: String)
    }
}