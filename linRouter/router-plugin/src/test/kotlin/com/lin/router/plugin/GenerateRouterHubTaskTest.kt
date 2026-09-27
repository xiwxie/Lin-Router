package com.lin.router.plugin

import java.io.File
import java.util.Properties
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 使用真实 Gradle 验证任务缓存、配置缓存及最后一个 Loader 删除后的重新生成。
 * @author pengshilin
 * @since 2026-09-27
 */
public class GenerateRouterHubTaskTest {
    /** 独立 Gradle fixture 和构建缓存，由 JUnit 管理生命周期。 */
    @get:Rule
    public val temporary: TemporaryFolder = TemporaryFolder()

    /** 源码之外的变动不会驱动汇总；删除输入后不能残留旧路由。 */
    @Test
    public fun cachesAndRebuildsAfterLastLoaderDeletion(): Unit {
        val root = temporary.root
        File(root, "settings.gradle").writeText("rootProject.name = 'hub-fixture'\nbuildCache { local { directory = file('test-cache') } }\n")
        val metadata = Properties()
        requireNotNull(javaClass.classLoader.getResourceAsStream("plugin-under-test-metadata.properties"))
            .use(metadata::load)
        val classpath = metadata.getProperty("implementation-classpath").split(File.pathSeparator)
            .joinToString(",") { "'" + it.replace("\\", "\\\\").replace("'", "\\'") + "'" }
        File(root, "build.gradle").writeText("""
            buildscript { dependencies { classpath files($classpath) } }
            tasks.register('hub', com.lin.router.plugin.GenerateRouterHubTask) {
                localLoaders.from(fileTree('loaders') { include '**/*Loader.kt' })
                currentModulePrefix.set('App')
                loaderOrder.set([])
                outputDirectory.set(layout.buildDirectory.dir('hub'))
            }
        """.trimIndent())
        val loader = File(root, "loaders/AppRouterLoader.kt").apply { parentFile.mkdirs(); writeText("// loader") }
        fun run() = GradleRunner.create().withProjectDir(root)
            .withArguments("hub", "--configuration-cache", "--build-cache", "--offline", "--stacktrace").build()
        assertEquals(TaskOutcome.SUCCESS, run().task(":hub")?.outcome)
        val second = run()
        assertEquals(TaskOutcome.UP_TO_DATE, second.task(":hub")?.outcome)
        assertTrue(second.output.contains("Reusing configuration cache."))
        File(root, "MainActivity.kt").writeText("// unrelated body edit")
        assertEquals(TaskOutcome.UP_TO_DATE, run().task(":hub")?.outcome)
        File(root, "build/hub").deleteRecursively()
        assertEquals(TaskOutcome.FROM_CACHE, run().task(":hub")?.outcome)
        loader.delete()
        assertEquals(TaskOutcome.SUCCESS, run().task(":hub")?.outcome)
        val hub = File(root, "build/hub/com/lin/router/generated/LinRouterAppHub.kt").readText()
        assertFalse(hub.contains("AppRouterLoader"))
        assertEquals(2, Regex("(?:return |=[ ]*)0").findAll(hub).count())
    }
}
