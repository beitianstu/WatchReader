// WatchReader 手机版：独立 Gradle 工程，手表端仍使用 tools/build-apk.ps1 的无 Gradle 构建。
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "watchreader-phone"

val mochiDir = file("third-party/mochi-compose")
require(mochiDir.isDirectory) {
    "缺少 third-party/mochi-compose。请先把上游 mochi-compose/build.gradle.kts、src、LICENSE、THIRD_PARTY_NOTICES 复制到 phone/third-party/mochi-compose。"
}

include(":app")
include(":plugin")
include(":mochi-compose")
project(":mochi-compose").projectDir = mochiDir
