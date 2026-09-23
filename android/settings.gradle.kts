pluginManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/central")
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/central")
        // ffmpegkit-maintained 坐标在阿里云镜像同步不全（有 POM 无 AAR），
        // 需直连 Maven Central 才能取到 AAR。
        mavenCentral()
        google()
    }
}

rootProject.name = "douyin-vocal-remover"

include(":app")
include(":douyin")
include(":audio-engine")
include(":settings")
include(":updater")
