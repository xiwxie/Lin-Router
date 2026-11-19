// minirouter-plugin/build.gradle.kts
plugins {
    // 声明这是一个使用 Kotlin 编写的 Gradle 插件
    `kotlin-dsl`
    id("maven-publish")
}

group = "com.lin.router.plugin"
version = libs.versions.linRouterRelease.get()

// 注册你的插件元数据（别人用的就是这里定义的 ID）
gradlePlugin {
    plugins {
        create("linRouter") {
            id = "com.lin.router.plugin"
            implementationClass = "com.lin.router.plugin.LinRouterPlugin"
        }
    }
}

dependencies {
    // 引入 Gradle 原生 API (kotlin-dsl 插件已默认包含部分，但保险起见声明一下)
    implementation(gradleApi())
    compileOnly(libs.android.tools.gradle)
    compileOnly(libs.symbol.processing.gradle.plugin)
    implementation(libs.asm)
    testImplementation(libs.junit)
    testImplementation(gradleTestKit())
}

// 兼容 Java 17 (AGP 8.0+ 强要求)，固定 Kotlin Toolchain 与语言版本以对齐 JitPack JDK 17 与 Gradle kotlin-dsl
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
    }
}