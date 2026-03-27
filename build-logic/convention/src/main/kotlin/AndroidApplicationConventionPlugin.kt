import com.android.build.api.dsl.ApplicationExtension
import com.misterp.build.plugin.ext.appApplyJvmConfig
import com.misterp.build.plugin.ext.buildCompileOptions
import com.misterp.build.plugin.ext.buildTypesTemp
import com.misterp.build.plugin.ext.findVersionToInt
import com.misterp.build.plugin.ext.findVersionToString
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.apply
import org.gradle.kotlin.dsl.configure

/**
 * @Description: Application-Module 通用模版
 * @Author: pengshilin
 * @CreateDate: 2025/7/16 15:14
 */
class AndroidApplicationConventionPlugin : Plugin<Project> {

    override fun apply(target: Project) {
        with(target) {
            apply(plugin = "com.android.application")
            apply(plugin = "org.jetbrains.kotlin.android")
            this.appApplyJvmConfig()
            extensions.configure<ApplicationExtension> {
                compileSdk = findVersionToInt("appCompileSdk")
                defaultConfig.apply {
                    targetSdk = findVersionToInt("appTargetSdk")
                    minSdk = findVersionToInt("appMinSdk")
                    versionCode = findVersionToInt("appVersionCode")
                    versionName = findVersionToString("appVersionName")
                    multiDexEnabled = true
                    vectorDrawables.useSupportLibrary = true
                    ndk {
                        // 设置支持的SO库架构
                        abiFilters.addAll(listOf("armeabi-v7a", "arm64-v8a"))
                    }
                    packaging {
                        resources {
                            excludes +=
                                listOf(
                                    "META-INF/library_release.kotlin_module",
                                )
                        }
                    }
                }
                sourceSets {
                    getByName("main") {
                        java.srcDirs("src/main/java")
                    }
                }
                buildCompileOptions(project)

                bundle {
                    language {
                        // 是否开启语言分包，当为true在这里可以添加inclue ‘ch-ZH’,配置预设语言
                        enableSplit = false
                    }
                    // 分辨率分包
                    density {
                        enableSplit = true
                    }
                    // cpu内核分包
                    abi {
                        enableSplit = true
                        // include "armeabi", "armeabi-v7a", "arm64-v8a", "x86", "x86_64"
                    }
                }

                packaging {
                    jniLibs {
                        useLegacyPackaging = true
                    }
                }

                //productFlavorsTmp()
                //signConfigTmp(target)
                buildTypesTemp()
            }
        }
    }

    /**
     * 签名模版配置
     */
    fun ApplicationExtension.signConfigTmp(project : Project){
        signingConfigs {
            create("config") {
                storeFile = project.file("./hawa.jks")
                storePassword = "hawa123"
                keyAlias = "hawa"
                keyPassword = "hawa123"
                enableV2Signing = true
            }
        }
    }

    /**
     * 渠道模版配置
     */
    fun ApplicationExtension.productFlavorsTmp(){
        productFlavors {
            create("google") {
                dimension = "hawa"
                buildConfigField("String", "HAWA_APP_ID", "\"xchat\"")
                buildConfigField("String", "APPSFLYER_ONELINK_KEY", "\"Burh\"")
                buildConfigField("String", "APP_STROE_CHANNEL", "\"xxm\"")
            }
            create("huawei") {
                dimension = "hawa"
                buildConfigField("String", "HAWA_APP_ID", "\"huawei\"")
                buildConfigField("String", "APPSFLYER_ONELINK_KEY", "\"Burh\"")
                buildConfigField("String", "APP_STROE_CHANNEL", "\"huawei\"")
            }
        }
    }

}