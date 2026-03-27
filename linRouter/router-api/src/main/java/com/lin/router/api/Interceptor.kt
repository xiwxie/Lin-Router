package com.lin.router.api

/**
 * @Author: pengshilin
 * @CreateDate: 2026/3/19 17:22
 * @Description: 
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class Interceptor(val priority: Int = 0)
