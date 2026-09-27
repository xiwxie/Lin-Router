package com.lin.router.plugin

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 覆盖类路径发现、删除、空模块与装载顺序，防止提速掩盖路由缺失。
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

    /** 本地最后一个 Loader 删除后仍保留依赖模块；全空时产出可用的空 Hub。 */
    @Test
    public fun deletionAndEmptyApplicationRemainValid(): Unit {
        val dependencyOnly = RouterHubSource.render(setOf("LibraryRouterLoader"), "App", emptyList()).toString()
        assertTrue(dependencyOnly.contains("LibraryRouterLoader().loadInto(routeMap)"))
        assertFalse(dependencyOnly.contains("AppRouterLoader"))
        val empty = RouterHubSource.render(emptySet(), "App", emptyList()).toString()
        assertTrue(empty.contains("class LinRouterAppHub : IRouterAppHub"))
        assertEquals(2, Regex("(?:return |=[ ]*)0").findAll(empty).count())
    }

    /** 输入枚举顺序不影响产物；显式依赖顺序生效，App 始终最后覆盖。 */
    @Test
    public fun orderingIsStableAndAppLoadsLast(): Unit {
        val names = linkedSetOf("ZRouterLoader", "AppRouterLoader", "ARouterLoader")
        val sorted = RouterHubSource.render(names, "App", emptyList()).toString()
        assertEquals(sorted, RouterHubSource.render(names.reversed().toSet(), "App", emptyList()).toString())
        val explicit = RouterHubSource.render(names, "App", listOf("Z", "A")).toString()
        assertTrue(explicit.indexOf("ZRouterLoader().loadInto") < explicit.indexOf("ARouterLoader().loadInto"))
        assertTrue(explicit.indexOf("ARouterLoader().loadInto") < explicit.indexOf("AppRouterLoader().loadInto"))
    }
}
