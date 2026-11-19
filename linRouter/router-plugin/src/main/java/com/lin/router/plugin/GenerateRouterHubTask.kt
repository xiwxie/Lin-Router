package com.lin.router.plugin

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
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes

/**
 * 从模块 Loader 直接生成固定名称的 AppHub 字节码；不解析业务源代码、不加载依赖类到 JVM。
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

    /** 每个变体独占的字节码输出目录，不包含业务 KSP 或 Kotlin 编译产物。 */
    @get:OutputDirectory
    public abstract val outputDirectory: DirectoryProperty

    /**
     * 全量重建很小的汇总字节码文件；输入未变时由 Gradle 跳过整个任务。
     * @return 无返回值；重复类名或不可读的依赖文件会使构建失败，避免静默丢路由。
     */
    @TaskAction
    public fun generate(): Unit {
        val names = RouterHubSource.collect(localLoaders.files, dependencyClasspath.files)
        val bytecode = RouterHubSource.renderBytecode(names, currentModulePrefix.get(), loaderOrder.get())
        val outRoot = outputDirectory.get().asFile
        if (outRoot.exists()) {
            outRoot.deleteRecursively()
        }
        val classFile = File(outRoot, RouterHubSource.HUB_CLASS_RELATIVE_PATH)
        classFile.parentFile.mkdirs()
        classFile.writeBytes(bytecode)
    }
}

/**
 * 无 Gradle 状态的 Loader 发现与 Hub 字节码生成器，供任务和回归测试共用。
 * @author pengshilin
 * @since 2026-09-27
 */
internal object RouterHubSource {
    /** 编译器的固定生成包；不是对任意业务类做后缀扫描。 */
    private const val GENERATED_PACKAGE: String = "com.lin.router.generated"

    /** 生成包对应的 JVM 内部路径前缀。 */
    private const val GENERATED_PACKAGE_PATH: String = "com/lin/router/generated/"

    /** 生成的 AppHub JVM 内部类名。 */
    private const val HUB_INTERNAL_NAME: String = "${GENERATED_PACKAGE_PATH}LinRouterAppHub"

    /** 运行时中枢接口 JVM 内部类名。 */
    private const val INTERFACE_INTERNAL_NAME: String = "com/lin/router/api/IRouterAppHub"

    /** 输出目录内 Hub 类文件的相对路径。 */
    internal const val HUB_CLASS_RELATIVE_PATH: String = "$HUB_INTERNAL_NAME.class"

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
     * 直接生成与 IRouterAppHub 契约一致的 JVM 字节码；依赖顺序确定，当前应用最后装载。
     * @param names Loader 简单类名。
     * @param currentPrefix 应用模块前缀。
     * @param preferredOrder 可选依赖模块顺序；未列出的模块按前缀排序。
     * @return 可直接合入 DEX/R8 的 LinRouterAppHub.class 字节码。
     */
    internal fun renderBytecode(names: Set<String>, currentPrefix: String, preferredOrder: List<String>): ByteArray {
        fun prefix(name: String): String = name.removeSuffix("InterceptorLoader").removeSuffix("RouterLoader")
        val ordered = names.sortedWith(compareBy<String>(
            { if (prefix(it) == currentPrefix) 1 else 0 },
            { preferredOrder.indexOf(prefix(it)).takeIf { index -> index >= 0 } ?: Int.MAX_VALUE },
            { prefix(it) }, { it }
        ))
        val routes = ordered.filter { it.endsWith("RouterLoader") }
        val interceptors = ordered.filter { it.endsWith("InterceptorLoader") }

        val cw = ClassWriter(ClassWriter.COMPUTE_MAXS)
        cw.visit(
            Opcodes.V1_8,
            Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL or Opcodes.ACC_SUPER,
            HUB_INTERNAL_NAME,
            null,
            "java/lang/Object",
            arrayOf(INTERFACE_INTERNAL_NAME)
        )

        // 生成 public 无参构造器（供 LinRouter 反射及 R8 keep 规则使用）
        cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
            visitInsn(Opcodes.RETURN)
            visitMaxs(0, 0)
            visitEnd()
        }

        emitCountMethod(cw, "getRouteCount", routes)
        emitCountMethod(cw, "getInterceptorCount", interceptors)

        val initSignature =
            "(Ljava/util/Map<Ljava/lang/String;Ljava/lang/Class<*>;>;Ljava/util/List<Lcom/lin/router/api/LinInterceptorMeta;>;)V"
        cw.visitMethod(Opcodes.ACC_PUBLIC, "init", "(Ljava/util/Map;Ljava/util/List;)V", initSignature, null).apply {
            visitCode()
            routes.forEach { loader ->
                val internal = "$GENERATED_PACKAGE_PATH$loader"
                visitTypeInsn(Opcodes.NEW, internal)
                visitInsn(Opcodes.DUP)
                visitMethodInsn(Opcodes.INVOKESPECIAL, internal, "<init>", "()V", false)
                visitVarInsn(Opcodes.ALOAD, 1)
                visitMethodInsn(Opcodes.INVOKEVIRTUAL, internal, "loadInto", "(Ljava/util/Map;)V", false)
            }
            interceptors.forEach { loader ->
                val internal = "$GENERATED_PACKAGE_PATH$loader"
                visitTypeInsn(Opcodes.NEW, internal)
                visitInsn(Opcodes.DUP)
                visitMethodInsn(Opcodes.INVOKESPECIAL, internal, "<init>", "()V", false)
                visitVarInsn(Opcodes.ALOAD, 2)
                visitMethodInsn(Opcodes.INVOKEVIRTUAL, internal, "loadInto", "(Ljava/util/List;)V", false)
            }
            visitInsn(Opcodes.RETURN)
            visitMaxs(0, 0)
            visitEnd()
        }

        cw.visitEnd()
        return cw.toByteArray()
    }

    /**
     * 生成 getRouteCount / getInterceptorCount 方法字节码，空集合直接返回 0。
     * @param cw 当前类字节码写入器。
     * @param methodName 方法名。
     * @param loaders 对应类型的 Loader 简单类名列表。
     */
    private fun emitCountMethod(cw: ClassWriter, methodName: String, loaders: List<String>): Unit {
        cw.visitMethod(Opcodes.ACC_PUBLIC, methodName, "()I", null, null).apply {
            visitCode()
            if (loaders.isEmpty()) {
                visitInsn(Opcodes.ICONST_0)
            } else {
                loaders.forEachIndexed { index, loader ->
                    val internal = "$GENERATED_PACKAGE_PATH$loader"
                    visitTypeInsn(Opcodes.NEW, internal)
                    visitInsn(Opcodes.DUP)
                    visitMethodInsn(Opcodes.INVOKESPECIAL, internal, "<init>", "()V", false)
                    visitMethodInsn(Opcodes.INVOKEVIRTUAL, internal, "getCheckCount", "()I", false)
                    if (index > 0) {
                        visitInsn(Opcodes.IADD)
                    }
                }
            }
            visitInsn(Opcodes.IRETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
    }
}
