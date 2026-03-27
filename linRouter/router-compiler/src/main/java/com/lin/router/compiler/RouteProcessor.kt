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

    override fun process(resolver: Resolver): List<KSAnnotated> {
        // 1. 获取并格式化模块名称（防呆设计：确保首字母大写且无非法字符）
        val rawName = options["routerModuleName"] ?: "App"
        // 防冲突命名转换算法
        val moduleName = rawName
            .removePrefix(":") // 剥离最前面的冒号
            .split(":", "-", "_") // 支持冒号、横杠、下划线三种主流分隔符
            .filter { it.isNotEmpty() } // 过滤掉空字符串
            .joinToString("") { part ->
                // 将每一段的首字母大写，拼接成完美的大驼峰
                part.replaceFirstChar { it.uppercase() }
            }
        // 【新增】：读取外部功能开关 (默认开启)
        val enableParamInject = (options["enableParamInject"] ?: "false").toBoolean()

        // 2. 核心任务 A：处理路由注解 @LinRoute
        processRoutes(resolver, moduleName)

        // 3. 核心任务 B：处理拦截器注解 @LinInterceptor
        processInterceptors(resolver, moduleName)

        // 只有开关开启时，才处理参数注入代码生成
        if (enableParamInject) {
            processParams(resolver)
        }

        return emptyList()
    }

    /**
     * 处理路由页面逻辑
     */
    private fun processRoutes(resolver: Resolver, moduleName: String) {
        val routeSymbols = resolver.getSymbolsWithAnnotation("com.lin.router.api.LinRoute")
        val routeMap = mutableMapOf<String, ClassName>()

        // 收集所有被 @LinRoute 标记的类
        routeSymbols.filterIsInstance<KSClassDeclaration>().forEach { classDecl ->
            val annotation = classDecl.annotations.first { a -> a.shortName.asString() == "LinRoute" }
            val path = annotation.arguments.first().value.toString()
            routeMap[path] = ClassName(classDecl.packageName.asString(), classDecl.simpleName.asString())
        }

        if (routeMap.isNotEmpty()) {
            val dependencies = Dependencies(true, *routeSymbols.mapNotNull { it.containingFile }.toList().toTypedArray())

            // 生成 Loader 类
            val implName = generateRouterLoaderClass(moduleName, routeMap, dependencies)

            // 生成 SPI 文件
            generateSpiFile("com.lin.router.api.IRouterLoader", implName, dependencies)
        }
    }

    /**
     * 处理拦截器逻辑
     */
    private fun processInterceptors(resolver: Resolver, moduleName: String) {
        val interceptorSymbols = resolver.getSymbolsWithAnnotation("com.lin.router.api.LinInterceptor")
        // 存储 ClassName 和 它的优先级 priority
        val interceptorList = mutableListOf<Pair<ClassName, Int>>()

        // 收集所有被 @LinInterceptor 标记的类
        interceptorSymbols.filterIsInstance<KSClassDeclaration>().forEach { classDecl ->
            val annotation = classDecl.annotations.first { a -> a.shortName.asString() == "LinInterceptor" }
            // 获取 priority 参数，如果没有传则默认为 0
            val priorityArg = annotation.arguments.firstOrNull { it.name?.asString() == "priority" }
            val priority = (priorityArg?.value as? Int) ?: 0

            val className = ClassName(classDecl.packageName.asString(), classDecl.simpleName.asString())
            interceptorList.add(Pair(className, priority))
        }

        if (interceptorList.isNotEmpty()) {
            val dependencies = Dependencies(true, *interceptorSymbols.mapNotNull { it.containingFile }.toList().toTypedArray())

            // 生成 Loader 类
            val implName = generateInterceptorLoaderClass(moduleName, interceptorList, dependencies)

            // 生成 SPI 文件
            generateSpiFile("com.lin.router.api.IInterceptorLoader", implName, dependencies)
        }
    }

    /**
     * 自动生成 Kotlin 代码：路由装载器
     */
    private fun generateRouterLoaderClass(moduleName: String, routes: Map<String, ClassName>, dependencies: Dependencies): String {
        val className = "${moduleName}RouterLoader"
        val packageName = "com.lin.router.generated"

        // 构建 MutableMap<String, Class<*>> 类型
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
                .addSuperinterface(ClassName("com.lin.router.api", "IRouterLoader"))
                .addFunction(loadIntoFun)
                .build())
            .build().writeTo(codeGenerator, dependencies)

        return "$packageName.$className"
    }

    /**
     * 自动生成 Kotlin 代码：拦截器装载器
     */
    private fun generateInterceptorLoaderClass(moduleName: String, interceptors: List<Pair<ClassName, Int>>, dependencies: Dependencies): String {
        val className = "${moduleName}InterceptorLoader"
        val packageName = "com.lin.router.generated"

        // 构建 MutableList<InterceptorMeta> 类型
        val metaClassName = ClassName("com.lin.router.api", "InterceptorMeta")
        val listType = ClassName("kotlin.collections", "MutableList").parameterizedBy(metaClassName)

        val loadIntoFun = FunSpec.builder("loadInto")
            .addModifiers(KModifier.OVERRIDE)
            .addParameter("list", listType)
            .apply {
                interceptors.forEach { (clazz, priority) ->
                    // 实例化对应的拦截器，并包裹在 InterceptorMeta 中
                    addStatement("list.add(%T(%L, %T()))", metaClassName, priority, clazz)
                }
            }
            .build()

        FileSpec.builder(packageName, className)
            .addType(TypeSpec.classBuilder(className)
                .addSuperinterface(ClassName("com.lin.router.api", "IInterceptorLoader"))
                .addFunction(loadIntoFun)
                .build())
            .build().writeTo(codeGenerator, dependencies)

        return "$packageName.$className"
    }

    /**
     * 自动生成 SPI 文本文件 (位于 META-INF/services/ 下)
     */
    private fun generateSpiFile(interfaceName: String, implName: String, dependencies: Dependencies) {
        try {
            codeGenerator.createNewFile(
                dependencies = dependencies,
                packageName = "META-INF.services",
                fileName = interfaceName,
                extensionName = ""
            ).use { output ->
                OutputStreamWriter(output).use { writer ->
                    writer.write("$implName\n")
                }
            }
        } catch (e: Exception) {
            logger.error("LinRouter: SPI 文件生成失败 - ${e.message}")
        }
    }

    /**
     * 处理 @LinParam 参数注入生成逻辑
     */
    private fun processParams(resolver: Resolver) {
        val paramSymbols = resolver.getSymbolsWithAnnotation("com.lin.router.api.LinParam")

        // 按所在的类进行分组。例如：UserActivity 下面有 3 个 @LinParam 变量
        val classGroup = mutableMapOf<KSClassDeclaration, MutableList<KSPropertyDeclaration>>()

        paramSymbols.filterIsInstance<KSPropertyDeclaration>().forEach { property ->
            val parentClass = property.parentDeclaration as? KSClassDeclaration
            if (parentClass != null) {
                classGroup.getOrPut(parentClass) { mutableListOf() }.add(property)
            }
        }

        // 为每个包含 @LinParam 的类，生成一个对应的 Xxx_LinInjector 辅助类
        classGroup.forEach { (classDecl, properties) ->
            generateInjectorClass(classDecl, properties)
        }
    }

    private fun generateInjectorClass(classDecl: KSClassDeclaration, properties: List<KSPropertyDeclaration>) {
        val packageName = classDecl.packageName.asString()
        val originalClassName = classDecl.simpleName.asString()
        val injectorClassName = "${originalClassName}_LinInjector"

        val dependencies = Dependencies(true, classDecl.containingFile!!)

        // 生成 override fun inject(target: Any)
        val injectFunBuilder = FunSpec.builder("inject")
            .addModifiers(KModifier.OVERRIDE)
            .addParameter("target", Any::class)
            .addStatement("val t = target as %T", ClassName(packageName, originalClassName))
            .addStatement("val bundle = if (t is android.app.Activity) t.intent?.extras else if (t is androidx.fragment.app.Fragment) t.arguments else null")
            .beginControlFlow("if (bundle != null)")

        // 遍历所有变量，生成从 bundle 取值的代码
        properties.forEach { property ->
            val propName = property.simpleName.asString()
            val annotation = property.annotations.first { it.shortName.asString() == "LinParam" }
            val argName = annotation.arguments.firstOrNull()?.value as? String
            // 如果注解没传名字，就默认用变量名
            val key = if (argName.isNullOrEmpty()) propName else argName

            val typeStr = property.type.resolve().declaration.qualifiedName?.asString()

            // 智能类型推导生成对应的 getString/getInt 等
            val getStatement = when (typeStr) {
                "kotlin.String" -> "bundle.getString(%S)"
                "kotlin.Int" -> "bundle.getInt(%S, t.$propName)" // 带有默认值保底
                "kotlin.Boolean" -> "bundle.getBoolean(%S, t.$propName)"
                "kotlin.Double" -> "bundle.getDouble(%S, t.$propName)"
                // 如果是复杂对象，直接统一走 get 等反序列化 (为了简化演示，这里泛指处理)
                else -> "bundle.get(%S) as? %T"
            }

            if (typeStr == "kotlin.String" || typeStr == "kotlin.Int" || typeStr == "kotlin.Boolean" || typeStr == "kotlin.Double") {
                injectFunBuilder.beginControlFlow("if (bundle.containsKey(%S))", key)
                injectFunBuilder.addStatement("t.$propName = $getStatement", key)
                injectFunBuilder.endControlFlow()
            } else {
                // 复杂对象处理
                injectFunBuilder.beginControlFlow("if (bundle.containsKey(%S))", key)
                injectFunBuilder.addStatement("t.$propName = $getStatement", key, property.type.toTypeName())
                injectFunBuilder.endControlFlow()
            }
        }

        injectFunBuilder.endControlFlow() // 结束 if (bundle != null)

        FileSpec.builder(packageName, injectorClassName)
            .addType(
                TypeSpec.classBuilder(injectorClassName)
                    .addSuperinterface(ClassName("com.lin.router.api", "IRouterInjector"))
                    .addFunction(injectFunBuilder.build())
                    .build()
            )
            .build()
            .writeTo(codeGenerator, dependencies)
    }
}