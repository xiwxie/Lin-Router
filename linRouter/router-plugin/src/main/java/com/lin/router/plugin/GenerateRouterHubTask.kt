package com.lin.router.plugin

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.STAR
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.asClassName
import java.io.File
import java.util.zip.ZipFile
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * 从模块 Loader 生成固定名称的 AppHub；不解析业务源代码、不加载依赖类到 JVM。
 * 即使删除最后一个 Loader，任务仍生成合法的空 Hub；不使用 SkipWhenEmpty。
 * @author pengshilin
 * @since 2026-09-27
 */
@CacheableTask
public abstract class GenerateRouterHubTask : DefaultTask() {
    /** 当前模块 KSP 生成的 Loader 文件；仅由 Gradle 在任务边界读取。 */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    public abstract val localLoaders: ConfigurableFileCollection

    /** 当前变体的已解析编译类路径；由 Gradle 做内容归一化及依赖调度。 */
    @get:Classpath
    public abstract val dependencyClasspath: ConfigurableFileCollection

    /** 当前应用的 Loader 前缀，保持应用路由最后装载。 */
    @get:Input
    public abstract val currentModulePrefix: Property<String>

    /** 可选的依赖 Loader 前缀顺序，用于固定历史重复路径覆盖顺序。 */
    @get:Input
    public abstract val loaderOrder: ListProperty<String>

    /** 每个变体独占的 Kotlin 输出目录，不包含业务 KSP 的产物。 */
    @get:OutputDirectory
    public abstract val outputDirectory: DirectoryProperty

    /**
     * 全量重建很小的汇总文件；输入未变时由 Gradle 跳过整个任务。
     * @return 无返回值；重复类名或不可读的依赖文件会使构建失败，避免静默丢路由。
     */
    @TaskAction
    public fun generate(): Unit {
        val names = RouterHubSource.collect(localLoaders.files, dependencyClasspath.files)
        val source = RouterHubSource.render(names, currentModulePrefix.get(), loaderOrder.get())
        source.writeTo(outputDirectory.get().asFile)
    }
}

/**
 * 无 Gradle 状态的 Loader 发现与源码生成器，供任务和回归测试共用。
 * @author pengshilin
 * @since 2026-09-27
 */
internal object RouterHubSource {
    /** 编译器的固定生成包；不是对任意业务类做后缀扫描。 */
    private const val GENERATED_PACKAGE: String = "com.lin.router.generated"

    /** 编译器生成的顶层 Loader 类名约束，不接受嵌套类。 */
    private val loaderPattern: Regex = Regex("[A-Za-z_][A-Za-z0-9_]*(RouterLoader|InterceptorLoader)")

    /**
     * 收集本地 Kotlin Loader 与依赖 JAR/目录中的 Loader 类，使用关闭式 ZIP 访问。
     * @param local 当前模块的生成文件。
     * @param classpath 当前变体真实编译类路径，不扫描未依赖的工程。
     * @return 唯一类名集合；重复类名立即失败。
     */
    internal fun collect(local: Set<File>, classpath: Set<File>): Set<String> {
        val owners = linkedMapOf<String, File>()
        fun add(name: String, owner: File) {
            if (!loaderPattern.matches(name)) return
            val previous = owners.putIfAbsent(name, owner)
            require(previous == null) { "Duplicate LinRouter loader $name in $previous and $owner" }
        }
        local.sortedBy(File::getPath).forEach { add(it.nameWithoutExtension, it) }
        val packagePath = GENERATED_PACKAGE.replace('.', '/') + "/"
        classpath.sortedBy(File::getPath).forEach { entry ->
            if (entry.isDirectory) {
                File(entry, packagePath).listFiles()?.filter { it.extension == "class" }
                    ?.forEach { add(it.nameWithoutExtension, it) }
            } else if (entry.isFile && entry.extension == "jar") {
                ZipFile(entry).use { zip ->
                    zip.entries().asSequence().filter { !it.isDirectory }.forEach { item ->
                        if (item.name.startsWith(packagePath) && item.name.endsWith(".class")) {
                            add(item.name.removePrefix(packagePath).removeSuffix(".class"), entry)
                        }
                    }
                }
            }
        }
        return owners.keys
    }

    /**
     * 生成与 IRouterAppHub 契约一致的静态调用；依赖顺序确定，当前应用最后装载。
     * @param names Loader 简单类名。
     * @param currentPrefix 应用模块前缀。
     * @param preferredOrder 可选依赖模块顺序；未列出的模块按前缀排序。
     * @return 可直接编译的 Hub 源码；不包含反射或运行时扫描。
     */
    internal fun render(names: Set<String>, currentPrefix: String, preferredOrder: List<String>): FileSpec {
        fun prefix(name: String): String = name.removeSuffix("InterceptorLoader").removeSuffix("RouterLoader")
        val ordered = names.sortedWith(compareBy<String>(
            { if (prefix(it) == currentPrefix) 1 else 0 },
            { preferredOrder.indexOf(prefix(it)).takeIf { index -> index >= 0 } ?: Int.MAX_VALUE },
            { prefix(it) }, { it }
        ))
        val routes = ordered.filter { it.endsWith("RouterLoader") }
        val interceptors = ordered.filter { it.endsWith("InterceptorLoader") }
        fun count(method: String, loaders: List<String>): FunSpec = FunSpec.builder(method)
            .addModifiers(KModifier.OVERRIDE).returns(Int::class).apply {
                if (loaders.isEmpty()) addStatement("return 0")
                else addStatement("return " + loaders.joinToString(" + ") { "%L().getCheckCount()" }, *loaders.toTypedArray())
            }.build()
        val init = FunSpec.builder("init").addModifiers(KModifier.OVERRIDE)
            .addParameter("routeMap", ClassName("kotlin.collections", "MutableMap")
                .parameterizedBy(String::class.asClassName(), ClassName("java.lang", "Class").parameterizedBy(STAR)))
            .addParameter("interceptors", ClassName("kotlin.collections", "MutableList")
                .parameterizedBy(ClassName("com.lin.router.api", "LinInterceptorMeta")))
            .apply {
                routes.forEach { addStatement("com.lin.router.generated.%L().loadInto(routeMap)", it) }
                interceptors.forEach { addStatement("com.lin.router.generated.%L().loadInto(interceptors)", it) }
            }.build()
        return FileSpec.builder(GENERATED_PACKAGE, "LinRouterAppHub")
            .addType(TypeSpec.classBuilder("LinRouterAppHub")
                .addSuperinterface(ClassName("com.lin.router.api", "IRouterAppHub"))
                .addFunction(count("getRouteCount", routes))
                .addFunction(count("getInterceptorCount", interceptors))
                .addFunction(init).build()).build()
    }
}
