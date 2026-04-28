package com.lin.router.compiler

import com.google.devtools.ksp.containingFile
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.STAR
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.asClassName
import com.squareup.kotlinpoet.ksp.toTypeName
import com.squareup.kotlinpoet.ksp.writeTo

class RouteProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
    private val options: Map<String, String>
) : SymbolProcessor {

    private var isAppHubGenerated = false
    private var isRouterLoaderGenerated = false
    private var isInterceptorLoaderGenerated = false

    // 【关键修复】：追踪当前模块是否真的生成了代码
    private var hasRoutesInCurrentModule = false
    private var hasInterceptorsInCurrentModule = false

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val rawModuleName = options["routerModuleName"] ?: "App"
        val moduleName = rawModuleName
            .removePrefix(":") 
            .split(":", "-", "_") 
            .filter { it.isNotEmpty() } 
            .joinToString("") { part ->
                part.replaceFirstChar { it.uppercase() }
            }
        val enableParamInject = (options["enableParamInject"] ?: "false").toBoolean()
        val isVerbose = (options["routerVerbose"] ?: "false").toBoolean()

        if (!isRouterLoaderGenerated) {
            hasRoutesInCurrentModule = processRoutes(resolver, moduleName)
            isRouterLoaderGenerated = true
        }

        if (!isInterceptorLoaderGenerated) {
            hasInterceptorsInCurrentModule = processInterceptors(resolver, moduleName)
            isInterceptorLoaderGenerated = true
        }

        val aggregateModules = options["routerAggregateModules"]
        if (!aggregateModules.isNullOrEmpty() && !isAppHubGenerated) {
            generateAppHub(resolver, aggregateModules.split(",").map { it.trim() }, rawModuleName, isVerbose)
            isAppHubGenerated = true
        }

        if (enableParamInject) {
            processParams(resolver)
        }

        return emptyList()
    }

    private fun generateAppHub(resolver: Resolver, moduleNames: List<String>, currentModulePath: String, isVerbose: Boolean) {
        val className = "LinRouterAppHub"
        val packageName = "com.lin.router.generated"
        
        if (isVerbose) logger.warn("LinRouter: [VERBOSE] 开始聚合路由中枢...")

        val mapType = ClassName("kotlin.collections", "MutableMap").parameterizedBy(
            String::class.asClassName(),
            ClassName("java.lang", "Class").parameterizedBy(STAR)
        )
        val metaClassName = ClassName("com.lin.router.api", "LinInterceptorMeta")
        val listType = ClassName("kotlin.collections", "MutableList").parameterizedBy(metaClassName)

        val initFun = FunSpec.builder("init")
            .addAnnotation(ClassName("kotlin.jvm", "JvmStatic"))
            .addParameter("routeMap", mapType)
            .addParameter("interceptors", listType)
            .apply {
                moduleNames.forEach { modulePath ->
                    val formattedName = modulePath
                        .removePrefix(":")
                        .split(":", "-", "_")
                        .filter { it.isNotEmpty() }
                        .joinToString("") { it.replaceFirstChar { c -> c.uppercase() } }

                    val isCurrent = (modulePath == currentModulePath)

                    // 1. 检查 RouterLoader
                    val routerExists = if (isCurrent) {
                        hasRoutesInCurrentModule
                    } else {
                        val fullName = "$packageName.${formattedName}RouterLoader"
                        resolver.getClassDeclarationByName(resolver.getKSNameFromString(fullName)) != null
                    }
                    if (routerExists) {
                        addStatement("%T().loadInto(routeMap)", ClassName(packageName, "${formattedName}RouterLoader"))
                        if (isVerbose) logger.warn("LinRouter: [VERBOSE]   已装载模块路由: $modulePath")
                    } else if (isVerbose) {
                        logger.warn("LinRouter: [VERBOSE]   跳过模块路由 (未发现 Loader): $modulePath")
                    }

                    // 2. 检查 InterceptorLoader
                    val interceptorExists = if (isCurrent) {
                        hasInterceptorsInCurrentModule
                    } else {
                        val fullName = "$packageName.${formattedName}InterceptorLoader"
                        resolver.getClassDeclarationByName(resolver.getKSNameFromString(fullName)) != null
                    }
                    if (interceptorExists) {
                        addStatement("%T().loadInto(interceptors)", ClassName(packageName, "${formattedName}InterceptorLoader"))
                        if (isVerbose) logger.warn("LinRouter: [VERBOSE]   已装载拦截器: $modulePath")
                    }
                }
            }
            .build()

        FileSpec.builder(packageName, className)
            .addType(TypeSpec.objectBuilder(className)
                .addFunction(initFun)
                .build())
            .build().writeTo(codeGenerator, Dependencies(true))
        
        logger.warn("LinRouter: 已生成 AppHub 路由中枢 (智能聚合模式)，包含模块: $moduleNames")
    }

    private fun processRoutes(resolver: Resolver, moduleName: String): Boolean {
        val routeSymbols = resolver.getSymbolsWithAnnotation("com.lin.router.api.LinRoute")
        val routeMap = mutableMapOf<String, ClassName>()

        routeSymbols.filterIsInstance<KSClassDeclaration>().forEach { classDecl ->
            val annotation = classDecl.annotations.first { a -> a.shortName.asString() == "LinRoute" }
            val path = annotation.arguments.first().value.toString()
            routeMap[path] = ClassName(classDecl.packageName.asString(), classDecl.simpleName.asString())
        }

        return if (routeMap.isNotEmpty()) {
            val dependencies = Dependencies(true, *routeSymbols.mapNotNull { it.containingFile }.toList().toTypedArray())
            generateRouterLoaderClass(moduleName, routeMap, dependencies)
            true
        } else {
            false
        }
    }

    private fun processInterceptors(resolver: Resolver, moduleName: String): Boolean {
        val interceptorSymbols = resolver.getSymbolsWithAnnotation("com.lin.router.api.LinInterceptor")
        val interceptorList = mutableListOf<Pair<ClassName, Int>>()

        interceptorSymbols.filterIsInstance<KSClassDeclaration>().forEach { classDecl ->
            val annotation = classDecl.annotations.first { a -> a.shortName.asString() == "LinInterceptor" }
            val priorityArg = annotation.arguments.firstOrNull { it.name?.asString() == "priority" }
            val priority = (priorityArg?.value as? Int) ?: 0
            val className = ClassName(classDecl.packageName.asString(), classDecl.simpleName.asString())
            interceptorList.add(Pair(className, priority))
        }

        return if (interceptorList.isNotEmpty()) {
            val dependencies = Dependencies(true, *interceptorSymbols.mapNotNull { it.containingFile }.toList().toTypedArray())
            generateInterceptorLoaderClass(moduleName, interceptorList, dependencies)
            true
        } else {
            false
        }
    }

    private fun generateRouterLoaderClass(moduleName: String, routes: Map<String, ClassName>, dependencies: Dependencies): String {
        val className = "${moduleName}RouterLoader"
        val packageName = "com.lin.router.generated"

        val mapType = ClassName("kotlin.collections", "MutableMap").parameterizedBy(
            String::class.asClassName(),
            ClassName("java.lang", "Class").parameterizedBy(STAR)
        )

        val loadIntoFun = FunSpec.builder("loadInto")
            .addModifiers(KModifier.OVERRIDE)
            .addParameter("map", mapType)
            .apply {
                routes.forEach { (path, clazz) ->
                    addStatement("map[%S] = %T::class.java", path, clazz)
                }
            }
            .build()

        FileSpec.builder(packageName, className)
            .addType(TypeSpec.classBuilder(className)
                .addSuperinterface(ClassName("com.lin.router.api", "LinRouterLoader"))
                .addFunction(loadIntoFun)
                .build())
            .build().writeTo(codeGenerator, dependencies)

        return "$packageName.$className"
    }

    private fun generateInterceptorLoaderClass(moduleName: String, interceptors: List<Pair<ClassName, Int>>, dependencies: Dependencies): String {
        val className = "${moduleName}InterceptorLoader"
        val packageName = "com.lin.router.generated"

        val metaClassName = ClassName("com.lin.router.api", "LinInterceptorMeta")
        val listType = ClassName("kotlin.collections", "MutableList").parameterizedBy(metaClassName)

        val loadIntoFun = FunSpec.builder("loadInto")
            .addModifiers(KModifier.OVERRIDE)
            .addParameter("list", listType)
            .apply {
                interceptors.forEach { (clazz, priority) ->
                    addStatement("list.add(%T(%L, %T()))", metaClassName, priority, clazz)
                }
            }
            .build()

        FileSpec.builder(packageName, className)
            .addType(TypeSpec.classBuilder(className)
                .addSuperinterface(ClassName("com.lin.router.api", "LinInterceptorLoader"))
                .addFunction(loadIntoFun)
                .build())
            .build().writeTo(codeGenerator, dependencies)

        return "$packageName.$className"
    }

    /**
     * 处理参数注入
     */
    private fun processParams(resolver: Resolver) {
        val paramSymbols = resolver.getSymbolsWithAnnotation("com.lin.router.api.LinParam")
        val classGroup = mutableMapOf<KSClassDeclaration, MutableList<KSPropertyDeclaration>>()

        paramSymbols.filterIsInstance<KSPropertyDeclaration>().forEach { property ->
            val parentClass = property.parentDeclaration as? KSClassDeclaration
            if (parentClass != null) {
                classGroup.getOrPut(parentClass) { mutableListOf() }.add(property)
            }
        }

        classGroup.forEach { (classDecl, properties) ->
            generateInjectorClass(classDecl, properties)
        }
    }

    /**
     * 生成Params注入
     */
    private fun generateInjectorClass(classDecl: KSClassDeclaration, properties: List<KSPropertyDeclaration>) {
        val packageName = classDecl.packageName.asString()
        val originalClassName = classDecl.simpleName.asString()
        val injectorClassName = "${originalClassName}_LinInjector"

        val dependencies = Dependencies(true, classDecl.containingFile!!)

        val injectFunBuilder = FunSpec.builder("inject")
            .addModifiers(KModifier.OVERRIDE)
            .addParameter("target", Any::class)
            .addStatement("val t = target as %T", ClassName(packageName, originalClassName))
            .addStatement("val bundle = if (t is android.app.Activity) t.intent?.extras else if (t is androidx.fragment.app.Fragment) t.arguments else null")
            .beginControlFlow("if (bundle != null)")

        properties.forEach { property ->
            val propName = property.simpleName.asString()
            val annotation = property.annotations.first { it.shortName.asString() == "LinParam" }
            val argName = annotation.arguments.firstOrNull()?.value as? String
            val key = if (argName.isNullOrEmpty()) propName else argName

            val typeStr = property.type.resolve().declaration.qualifiedName?.asString()

            val getStatement = when (typeStr) {
                "kotlin.String" -> "bundle.getString(%S)"
                "kotlin.Int" -> "bundle.getInt(%S, t.$propName)"
                "kotlin.Boolean" -> "bundle.getBoolean(%S, t.$propName)"
                "kotlin.Double" -> "bundle.getDouble(%S, t.$propName)"
                else -> "bundle.get(%S) as? %T"
            }

            if (typeStr == "kotlin.String" || typeStr == "kotlin.Int" || typeStr == "kotlin.Boolean" || typeStr == "kotlin.Double") {
                injectFunBuilder.beginControlFlow("if (bundle.containsKey(%S))", key)
                injectFunBuilder.addStatement("t.$propName = $getStatement", key)
                injectFunBuilder.endControlFlow()
            } else {
                injectFunBuilder.beginControlFlow("if (bundle.containsKey(%S))", key)
                injectFunBuilder.addStatement("t.$propName = $getStatement", key, property.type.toTypeName())
                injectFunBuilder.endControlFlow()
            }
        }

        injectFunBuilder.endControlFlow()

        FileSpec.builder(packageName, injectorClassName)
            .addType(
                TypeSpec.classBuilder(injectorClassName)
                    .addSuperinterface(ClassName("com.lin.router.api", "LinRouterInjector"))
                    .addFunction(injectFunBuilder.build())
                    .build()
            )
            .build()
            .writeTo(codeGenerator, dependencies)
    }
}
