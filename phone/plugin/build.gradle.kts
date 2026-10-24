plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// 插件沿用现有 local-signing.keystore / .password（tools/build-apk.ps1 生成）。
// 只交给构建系统做签名，不打印、不复制、不生成新密钥；缺失就失败，避免证书 pin 失效。
val pluginKeystore = rootProject.file("../fanqie/build/local-signing.keystore")
val pluginPasswordFile = rootProject.file("../fanqie/build/local-signing.password")
require(pluginKeystore.isFile && pluginPasswordFile.isFile) {
    "缺少插件签名材料：${pluginKeystore.path} / ${pluginPasswordFile.path}。" +
        "请先用 fanqie/tools/build-apk.ps1 生成；本工程不会生成替代密钥。"
}
val pluginPassword = providers
    .fileContents(layout.projectDirectory.file("../../fanqie/build/local-signing.password"))
    .asText.get().trim()

android {
    namespace = "com.watchreader.fanqie"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.watchreader.fanqie"
        minSdk = 29
        targetSdk = 29
        versionCode = 8
        versionName = "0.3.0"
    }

    signingConfigs {
        create("plugin") {
            storeFile = pluginKeystore
            storePassword = pluginPassword
            keyAlias = "fanqie-watch"
            keyPassword = pluginPassword
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("plugin")
        }
        getByName("release") {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("plugin")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    // 复用插件既有 Java 实现（Provider/加密/网络/旧账号页原样编译）。
    // 旧 PluginSetupActivity.java 仍编入以保持源码/手工构建一致，但 Manifest 不再直接注册它；
    // 组件名 com.watchreader.fanqie.PluginSetupActivity 通过 activity-alias 指向 Mochi 版。
    sourceSets {
        getByName("main") {
            java.srcDirs(
                "../shared-ui/src/main/java",
                "../../fanqie/android/java",
                "../../fanqie/api/java",
            )
            res.srcDirs("../../fanqie/android/res")
        }
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/LICENSE.md",
            "/META-INF/LICENSE-notice.md",
        )
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":mochi-compose"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
}
