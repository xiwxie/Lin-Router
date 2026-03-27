package com.lin.router.api

@Target(AnnotationTarget.FIELD)
@Retention(AnnotationRetention.BINARY)
annotation class LinParam(val name: String = "")