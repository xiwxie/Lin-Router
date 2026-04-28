plugins {
    alias(libs.plugins.nowinandroid.maven.publish)
    id("org.jetbrains.kotlin.jvm")
}
publishInfo {
    artifactId = "router-compiler"
    version = "1.0.8-SNAPSHOT"
    description = "路由框架APT"
    groupId = "com.lin.lib.router"
}
dependencies {
    implementation(libs.symbol.processing.api)
    implementation(libs.kotlinpoet)
    implementation(libs.kotlinpoet.ksp)
}