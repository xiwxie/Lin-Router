package com.lin.router.compiler

import com.google.devtools.ksp.containingFile
import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.*
import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.ksp.toTypeName
import com.squareup.kotlinpoet.ksp.writeTo
import java.io.OutputStreamWriter

class RouteProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
    private val options: Map<String, String>
) : SymbolProcessor {

    private var isAppHubGenerated = false
    private var isRouterLoaderGenerated = false
    private var isInterceptorLoaderGenerated = false

    private var hasRoutesInCurrentModule = false
    private var hasInterceptorsInCurrentModule = false
    
    private var currentModuleRouteCount = 0
    private var currentModuleInterceptorCount = 0

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val rawModuleName = options["routerModuleName"] ?: "App"
        val moduleName = formatModuleName(rawModuleName)
        
        val enableParamInject = (options["enableParamInject"] ?: "false").toBoolean()
        val isVerbose = (options["routerVerbose"] ?: "false").toBoolean()

        if (!isRouterLoaderGenerated) {
            processRoutes(resolver, moduleName)
            isRouterLoaderGenerated = true
        }

        if (!isInterceptorLoaderGenerated) {
            processInterceptors(resolver, moduleName)
            isInterceptorLoaderGenerated = true
        }

        val aggregateModules = options["routerAggregateModules"]
        if (!aggregateModules.isNullOrEmpty() && !isAppHubGenerated) {
            val allFiles = resolver.getAllFiles().toList().toTypedArray()
            generateAppHub(resolver, aggregateModules.split(",").filter { it.isNotBlank() }, rawModuleName, isVerbose, Dependencies(true, *allFiles))
            isAppHubGenerated = true
        }

        if (enableParamInject) {
            processParams(resolver)
        }

        return emptyList()
    }

    private fun formatModuleName(path: String): String {
        return path.removePrefix(":")
            .split(":")
            .filter { it.isNotEmpty() }
            .joinToString("_") { part ->
                val cleanPart = part.replace(Regex("[^a-zA-Z0-9_]"), "")
                if (cleanPart.isEmpty()) return@joinToString ""
                cleanPart.split("-", "_").joinToString("") { 
                    it.replaceFirstChar { c -> if (c.isLowerCase()) c.titlecase() else c.toString() } 
                }
            }
    }

    private fun generateAppHub(resolver: Resolver, modulePaths: List<String>, currentModulePath: String, isVerbose: Boolean, dependencies: Dependencies) {
        val className = "LinRouterAppHub"
        val packageName = "com.lin.router.generated"

        val activeRouterLoaders = mutableListOf<String>()
        val activeInterceptorLoaders = mutableListOf<String>()
        
        val allPaths = (modulePaths + currentModulePath).distinct()
        allPaths.forEach { path ->
            val fmt = formatModuleName(path)
            if (fmt.isEmpty()) return@forEach
            val isCur = (path == currentModulePath)
            
            val rName = "${fmt}RouterLoader"
            if (isCur) {
                if (hasRoutesInCurrentModule) activeRouterLoaders.add(rName)
            } else {
                if (resolver.getClassDeclarationByName(resolver.getKSNameFromString("$packageName.$rName")) != null) {
                    activeRouterLoaders.add(rName)
                }
            }
            
            val iName = "${fmt}InterceptorLoader"
            if (isCur) {
                if (hasInterceptorsInCurrentModule) activeInterceptorLoaders.add(iName)
            } else {
                if (resolver.getClassDeclarationByName(resolver.getKSNameFromString("$packageName.$iName")) != null) {
                    activeInterceptorLoaders.add(iName)
                }
            }
        }

        // 🚀 不呆方案：利用接口方法 getCheckCount() 进行精准运行时累加
        val getRCountFun = FunSpec.builder("getRouteCount")
            .addModifiers(KModifier.OVERRIDE)
            .returns(Int::class)
            .apply {
                if (activeRouterLoaders.isEmpty()) {
                    addStatement("return 0")
                } else {
                    val sumExpr = activeRouterLoaders.joinToString(" + ") { "%L().getCheckCount()" }
                    addStatement("return $sumExpr", *activeRouterLoaders.toTypedArray())
                }
            }.build()

        val getICountFun = FunSpec.builder("getInterceptorCount")
            .addModifiers(KModifier.OVERRIDE)
            .returns(Int::class)
            .apply {
                if (activeInterceptorLoaders.isEmpty()) {
                    addStatement("return 0")
                } else {
                    val sumExpr = activeInterceptorLoaders.joinToString(" + ") { "%L().getCheckCount()" }
                    addStatement("return $sumExpr", *activeInterceptorLoaders.toTypedArray())
                }
            }.build()

        val initFun = FunSpec.builder("init")
            .addModifiers(KModifier.OVERRIDE)
            .addParameter("routeMap", ClassName("kotlin.collections", "MutableMap").parameterizedBy(String::class.asClassName(), ClassName("java.lang", "Class").parameterizedBy(STAR)))
            .addParameter("interceptors", ClassName("kotlin.collections", "MutableList").parameterizedBy(ClassName("com.lin.router.api", "LinInterceptorMeta")))
            .apply {
                activeRouterLoaders.forEach { addStatement("com.lin.router.generated.%L().loadInto(routeMap)", it) }
                activeInterceptorLoaders.forEach { addStatement("com.lin.router.generated.%L().loadInto(interceptors)", it) }
            }.build()

        try {
            FileSpec.builder(packageName, className)
                .addType(TypeSpec.classBuilder(className)
                    .addSuperinterface(ClassName("com.lin.router.api", "IRouterAppHub"))
                    .addFunction(getRCountFun)
                    .addFunction(getICountFun)
                    .addFunction(initFun)
                    .build())
                .build().writeTo(codeGenerator, dependencies)
        } catch (e: Exception) {
            logger.error("LinRouter: [Processor] AppHub 写入失败: ${e.message}")
        }
    }

    private fun processRoutes(resolver: Resolver, moduleName: String) {
        val routeSymbols = resolver.getSymbolsWithAnnotation("com.lin.router.api.LinRoute")
        val routeMap = mutableMapOf<String, ClassName>()
        routeSymbols.filterIsInstance<KSClassDeclaration>().forEach { classDecl ->
            val annotation = classDecl.annotations.first { a -> a.shortName.asString() == "LinRoute" }
            val path = annotation.arguments.first().value.toString()
            routeMap[path] = ClassName(classDecl.packageName.asString(), classDecl.simpleName.asString())
        }
        if (routeMap.isNotEmpty()) {
            val dependencies = Dependencies(true, *routeSymbols.mapNotNull { it.containingFile }.toList().toTypedArray())
            generateRouterLoaderClass(moduleName, routeMap, dependencies)
            hasRoutesInCurrentModule = true
            currentModuleRouteCount = routeMap.size
        }
    }

    private fun processInterceptors(resolver: Resolver, moduleName: String) {
        val interceptorSymbols = resolver.getSymbolsWithAnnotation("com.lin.router.api.LinInterceptor")
        val interceptorList = mutableListOf<Pair<ClassName, Int>>()
        interceptorSymbols.filterIsInstance<KSClassDeclaration>().forEach { classDecl ->
            val annotation = classDecl.annotations.first { a -> a.shortName.asString() == "LinInterceptor" }
            val priority = (annotation.arguments.firstOrNull { it.name?.asString() == "priority" }?.value as? Int) ?: 0
            interceptorList.add(Pair(ClassName(classDecl.packageName.asString(), classDecl.simpleName.asString()), priority))
        }
        if (interceptorList.isNotEmpty()) {
            val dependencies = Dependencies(true, *interceptorSymbols.mapNotNull { it.containingFile }.toList().toTypedArray())
            generateInterceptorLoaderClass(moduleName, interceptorList, dependencies)
            hasInterceptorsInCurrentModule = true
            currentModuleInterceptorCount = interceptorList.size
        }
    }

    private fun generateRouterLoaderClass(moduleName: String, routes: Map<String, ClassName>, dependencies: Dependencies) {
        val className = "${moduleName}RouterLoader"
        val packageName = "com.lin.router.generated"
        
        val loadIntoFun = FunSpec.builder("loadInto")
            .addModifiers(KModifier.OVERRIDE)
            .addParameter("map", ClassName("kotlin.collections", "MutableMap").parameterizedBy(String::class.asClassName(), ClassName("java.lang", "Class").parameterizedBy(STAR)))
            .apply { routes.forEach { (path, clazz) -> addStatement("map[%S] = %T::class.java", path, clazz) } }
            .build()

        val getCountFun = FunSpec.builder("getCheckCount")
            .addModifiers(KModifier.OVERRIDE)
            .returns(Int::class)
            .addStatement("return %L", routes.size)
            .build()

        FileSpec.builder(packageName, className)
            .addType(TypeSpec.classBuilder(className)
                .addSuperinterface(ClassName("com.lin.router.api", "LinRouterLoader"))
                .addFunction(loadIntoFun)
                .addFunction(getCountFun)
                .build())
            .build().writeTo(codeGenerator, dependencies)
    }

    private fun generateInterceptorLoaderClass(moduleName: String, interceptors: List<Pair<ClassName, Int>>, dependencies: Dependencies) {
        val className = "${moduleName}InterceptorLoader"
        val packageName = "com.lin.router.generated"

        val loadIntoFun = FunSpec.builder("loadInto")
            .addModifiers(KModifier.OVERRIDE)
            .addParameter("list", ClassName("kotlin.collections", "MutableList").parameterizedBy(ClassName("com.lin.router.api", "LinInterceptorMeta")))
            .apply { interceptors.forEach { (clazz, priority) -> addStatement("list.add(com.lin.router.api.LinInterceptorMeta(%L, %T()))", priority, clazz) } }
            .build()

        val getCountFun = FunSpec.builder("getCheckCount")
            .addModifiers(KModifier.OVERRIDE)
            .returns(Int::class)
            .addStatement("return %L", interceptors.size)
            .build()

        FileSpec.builder(packageName, className)
            .addType(TypeSpec.classBuilder(className)
                .addSuperinterface(ClassName("com.lin.router.api", "LinInterceptorLoader"))
                .addFunction(loadIntoFun)
                .addFunction(getCountFun)
                .build())
            .build().writeTo(codeGenerator, dependencies)
    }

    private fun processParams(resolver: Resolver) {
        val paramSymbols = resolver.getSymbolsWithAnnotation("com.lin.router.api.LinParam")
        val classGroup = mutableMapOf<KSClassDeclaration, MutableList<KSPropertyDeclaration>>()
        paramSymbols.filterIsInstance<KSPropertyDeclaration>().forEach { property ->
            val parentClass = property.parentDeclaration as? KSClassDeclaration
            if (parentClass != null) {
                classGroup.getOrPut(parentClass) { mutableListOf() }.add(property)
            }
        }
        classGroup.forEach { (classDecl, properties) -> generateInjectorClass(classDecl, properties) }
    }

    private fun generateInjectorClass(classDecl: KSClassDeclaration, properties: List<KSPropertyDeclaration>) {
        val packageName = classDecl.packageName.asString()
        val originalClassName = classDecl.simpleName.asString()
        val injectorClassName = "${originalClassName}_LinInjector"
        val dependencies = Dependencies(false, classDecl.containingFile!!)
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
            injectFunBuilder.beginControlFlow("if (bundle.containsKey(%S))", key)
            if (typeStr in listOf("kotlin.String", "kotlin.Int", "kotlin.Boolean", "kotlin.Double")) {
                injectFunBuilder.addStatement("t.$propName = $getStatement", key)
            } else {
                injectFunBuilder.addStatement("t.$propName = $getStatement", key, property.type.toTypeName())
            }
            injectFunBuilder.endControlFlow()
        }
        injectFunBuilder.endControlFlow()
        FileSpec.builder(packageName, injectorClassName)
            .addType(TypeSpec.classBuilder(injectorClassName)
                .addSuperinterface(ClassName("com.lin.router.api", "LinRouterInjector"))
                .addFunction(injectFunBuilder.build())
                .build())
            .build().writeTo(codeGenerator, dependencies)
    }
}
