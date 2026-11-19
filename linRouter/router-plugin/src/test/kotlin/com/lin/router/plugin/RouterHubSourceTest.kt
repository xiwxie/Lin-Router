package com.lin.router.plugin

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/**
 * 覆盖类路径发现、删除、空模块、装载顺序及生成的 JVM 字节码可执行性。
 * @author pengshilin
 * @since 2026-09-27
 */
public class RouterHubSourceTest {
    /** 每个测试独立的文件树，由 JUnit 负责清理。 */
    @get:Rule
    public val temporary: TemporaryFolder = TemporaryFolder()

    /** 依赖 JAR 与类目录均可发现，嵌套类及其他包被排除。 */
    @Test
    public fun discoversOnlyTopLevelGeneratedLoaders(): Unit {
        val jar = temporary.newFile("dependency.jar")
        ZipOutputStream(jar.outputStream()).use { zip ->
            listOf("com/lin/router/generated/LibraryRouterLoader.class",
                "com/lin/router/generated/LibraryRouterLoader\$Helper.class",
                "other/FakeRouterLoader.class").forEach {
                zip.putNextEntry(ZipEntry(it)); zip.write(byteArrayOf(0)); zip.closeEntry()
            }
        }
        val classes = temporary.newFolder("classes")
        File(classes, "com/lin/router/generated/LibraryInterceptorLoader.class").apply {
            parentFile.mkdirs(); writeBytes(byteArrayOf(0))
        }
        val app = temporary.newFile("AppRouterLoader.kt")
        assertEquals(setOf("AppRouterLoader", "LibraryRouterLoader", "LibraryInterceptorLoader"),
            RouterHubSource.collect(setOf(app), setOf(jar, classes)))
    }

    /** 两个产物提供相同 Loader 时必须失败，不能静默吞掉其中一个模块。 */
    @Test(expected = IllegalArgumentException::class)
    public fun rejectsDuplicateLoaderNames(): Unit {
        val first = temporary.newFolder("first")
        val second = temporary.newFolder("second")
        RouterHubSource.collect(setOf(File(first, "AppRouterLoader.kt"), File(second, "AppRouterLoader.kt")), emptySet())
    }

    /** 本地最后一个 Loader 删除后仍保留依赖模块；全空时产出可加载执行的空 Hub 字节码。 */
    @Test
    public fun deletionAndEmptyApplicationRemainValid(): Unit {
        val dependencyOnly = RouterHubSource.renderBytecode(setOf("LibraryRouterLoader"), "App", emptyList())
        val calls = collectMethodCalls(dependencyOnly, "init")
        assertTrue(calls.contains("com/lin/router/generated/LibraryRouterLoader.loadInto"))
        assertFalse(calls.any { it.contains("AppRouterLoader") })

        val empty = RouterHubSource.renderBytecode(emptySet(), "App", emptyList())
        val cl = BytecodeClassLoader(
            mapOf(
                "com.lin.router.api.IRouterAppHub" to buildStubInterface("com/lin/router/api/IRouterAppHub"),
                "com.lin.router.generated.LinRouterAppHub" to empty
            )
        )
        val hubClass = cl.loadClass("com.lin.router.generated.LinRouterAppHub")
        val hub = hubClass.getDeclaredConstructor().newInstance()
        assertEquals(0, hubClass.getMethod("getRouteCount").invoke(hub))
        assertEquals(0, hubClass.getMethod("getInterceptorCount").invoke(hub))
        hubClass.getMethod("init", MutableMap::class.java, MutableList::class.java)
            .invoke(hub, mutableMapOf<String, Class<*>>(), mutableListOf<Any>())
    }

    /** 输入枚举顺序不影响字节码；显式依赖顺序生效，App 始终最后覆盖，且可实际加载执行。 */
    @Test
    public fun orderingIsStableAndAppLoadsLast(): Unit {
        val names = linkedSetOf("ZRouterLoader", "AppRouterLoader", "ARouterLoader")
        val sorted = RouterHubSource.renderBytecode(names, "App", emptyList())
        assertArrayEquals(sorted, RouterHubSource.renderBytecode(names.reversed().toSet(), "App", emptyList()))

        val explicit = RouterHubSource.renderBytecode(names, "App", listOf("Z", "A"))
        val calls = collectMethodCalls(explicit, "init")
        val zIdx = calls.indexOf("com/lin/router/generated/ZRouterLoader.loadInto")
        val aIdx = calls.indexOf("com/lin/router/generated/ARouterLoader.loadInto")
        val appIdx = calls.indexOf("com/lin/router/generated/AppRouterLoader.loadInto")
        assertTrue(zIdx >= 0 && zIdx < aIdx && aIdx < appIdx)

        // 真实加载并执行生成的字节码，验证栈帧、方法签名与覆盖顺序。
        val classes = mutableMapOf(
            "com.lin.router.api.IRouterAppHub" to buildStubInterface("com/lin/router/api/IRouterAppHub"),
            "com.lin.router.generated.LinRouterAppHub" to explicit,
            "com.lin.router.generated.ZRouterLoader" to buildStubLoader("com/lin/router/generated/ZRouterLoader", "/dup", String::class.java, 2),
            "com.lin.router.generated.ARouterLoader" to buildStubLoader("com/lin/router/generated/ARouterLoader", "/dup", Int::class.javaObjectType, 3),
            "com.lin.router.generated.AppRouterLoader" to buildStubLoader("com/lin/router/generated/AppRouterLoader", "/dup", Long::class.javaObjectType, 5)
        )
        val cl = BytecodeClassLoader(classes)
        val hubClass = cl.loadClass("com.lin.router.generated.LinRouterAppHub")
        val hub = hubClass.getDeclaredConstructor().newInstance()
        assertEquals(10, hubClass.getMethod("getRouteCount").invoke(hub))
        assertEquals(0, hubClass.getMethod("getInterceptorCount").invoke(hub))
        val routeMap = mutableMapOf<String, Class<*>>()
        hubClass.getMethod("init", MutableMap::class.java, MutableList::class.java)
            .invoke(hub, routeMap, mutableListOf<Any>())
        assertEquals(Long::class.javaObjectType, routeMap["/dup"])
    }

    private fun collectMethodCalls(bytecode: ByteArray, targetMethod: String): List<String> {
        val calls = mutableListOf<String>()
        ClassReader(bytecode).accept(object : ClassVisitor(Opcodes.ASM9) {
            override fun visitMethod(
                access: Int,
                name: String?,
                descriptor: String?,
                signature: String?,
                exceptions: Array<out String>?
            ): MethodVisitor? {
                if (name != targetMethod) return null
                return object : MethodVisitor(Opcodes.ASM9) {
                    override fun visitMethodInsn(
                        opcode: Int,
                        owner: String,
                        methodName: String,
                        desc: String,
                        isInterface: Boolean
                    ) {
                        calls.add("$owner.$methodName")
                    }
                }
            }
        }, 0)
        return calls
    }

    private fun buildStubInterface(internalName: String): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC or Opcodes.ACC_INTERFACE or Opcodes.ACC_ABSTRACT, internalName, null, "java/lang/Object", null)
        cw.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT, "getRouteCount", "()I", null, null).visitEnd()
        cw.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT, "getInterceptorCount", "()I", null, null).visitEnd()
        cw.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT, "init", "(Ljava/util/Map;Ljava/util/List;)V", null, null).visitEnd()
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun buildStubLoader(internalName: String, key: String, target: Class<*>, count: Int): ByteArray {
        val cw = ClassWriter(ClassWriter.COMPUTE_MAXS)
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC or Opcodes.ACC_SUPER, internalName, null, "java/lang/Object", null)
        cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
            visitInsn(Opcodes.RETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        cw.visitMethod(Opcodes.ACC_PUBLIC, "getCheckCount", "()I", null, null).apply {
            visitCode()
            visitIntInsn(Opcodes.BIPUSH, count)
            visitInsn(Opcodes.IRETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        cw.visitMethod(Opcodes.ACC_PUBLIC, "loadInto", "(Ljava/util/Map;)V", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 1)
            visitLdcInsn(key)
            visitLdcInsn(org.objectweb.asm.Type.getType(target))
            visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Map", "put", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true)
            visitInsn(Opcodes.POP)
            visitInsn(Opcodes.RETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private class BytecodeClassLoader(private val definitions: Map<String, ByteArray>) : ClassLoader(RouterHubSourceTest::class.java.classLoader) {
        override fun findClass(name: String): Class<*> {
            val bytes = definitions[name] ?: return super.findClass(name)
            return defineClass(name, bytes, 0, bytes.size)
        }
    }
}
