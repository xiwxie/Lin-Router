package com.lin.router.api

@Target(AnnotationTarget.FIELD)
@Retention(AnnotationRetention.BINARY)
public annotation class LinParam(val name: String = "")