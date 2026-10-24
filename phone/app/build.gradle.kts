plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// 复用现有手表主程序的签名身份（android/build/debug.keystore，alias watchreader）。
// 包名保持 com.watchreader，插件按证书互相校验；同包只能覆盖升级，不能与旧版并装。
// 缺少签名库时直接失败：绝不退回 Android 默认 debug 签名，也不自动生成新密钥，否则 pin 失效。
val hostKeystore = rootProject.file("../android/build/debug.keystore")
require(hostKeystore.isFile) {
    "缺少现有 WatchReader 签名库 ${hostKeystore.path}。请先用 tools/build-apk.ps1 生成/恢复它；" +
        "本工程不会生成新密钥，也不会退回默认 debug 签名，否则番茄插件证书 pin 会失效。"
}

android {
    namespace = "com.watchreader.phone"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.watchreader"
        minSdk = 29
        targetSdk = 29
        versionCode = 6
        versionName = "1.3-phone"
        testInstrumentationRunner = "com.watchreader.ReaderPagingInstrumentation"
    }

    signingConfigs {
        create("host") {
            storeFile = hostKeystore
            storePassword = "android"
            keyAlias = "watchreader"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("host")
        }
        getByName("release") {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("host")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    // 复用旧 Java 引擎与业务：手表主程序源码、插件 API、主机侧适配层。
    sourceSets {
        getByName("main") {
            java.srcDirs(
                "../shared-ui/src/main/java",
                "../../android/app/java",
                "../../fanqie/api/java",
                "../../fanqie/host/java",
            )
        }
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/LICENSE.md",
            "/META-INF/LICENSE-notice.md",
        )
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
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

    testImplementation(libs.junit)
}
