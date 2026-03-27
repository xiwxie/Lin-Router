plugins {
    alias(libs.plugins.nowinandroid.maven.publish)
    id("org.jetbrains.kotlin.jvm")
}
publishInfo {
    artifactId = "router-compiler"
    version = "1.0.0-SNAPSHOT"
    description = "路由框架APT"
    groupId = "com.lin.lib.router"
}
dependencies {
    implementation("com.google.devtools.ksp:symbol-processing-api:1.9.20-1.0.14")
    implementation("com.squareup:kotlinpoet:1.14.2")
    implementation("com.squareup:kotlinpoet-ksp:1.14.2")
}