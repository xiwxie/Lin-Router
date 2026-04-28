plugins {
    alias(libs.plugins.nowinandroid.android.library)
    alias(libs.plugins.nowinandroid.maven.publish)
}
publishInfo {
    artifactId = "router-api"
    version = "1.0.8-SNAPSHOT"
    description = "路由框架API"
    groupId = "com.lin.lib.router"
}
android {
    namespace = "routerApi"

    defaultConfig {

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
}
dependencies {
    compileOnly(libs.androidx.appcompat)
    compileOnly(libs.androidx.fragment.ktx)
}
