plugins {
    alias(libs.plugins.nowinandroid.android.application)
}

android {
    namespace = "com.example.lin_router"
    defaultConfig {
        applicationId = "com.misterp.lin.router"
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
}