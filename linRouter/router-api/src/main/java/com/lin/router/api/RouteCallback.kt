package com.lin.router.api

public interface RouteCallback {
    public fun onFound(request: RouteRequest) {}
    public fun onLost(request: RouteRequest) {}
    public fun onArrival(request: RouteRequest) {}
    public fun onInterrupt(request: RouteRequest, reason: String) {}
}