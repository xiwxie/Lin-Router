package com.lin.router.api

/**
 * @Author: pengshilin
 * @CreateDate: 2026/3/19 17:21
 * @Description: 
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
public annotation class LinRoute(val path: String)
