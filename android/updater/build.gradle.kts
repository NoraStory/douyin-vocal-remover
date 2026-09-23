import java.util.Properties

plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.nora.douyinremover.updater"
    compileSdk = 37

    defaultConfig {
        minSdk = 26

        // Gitee 镜像仓库坐标：从根目录 local.properties 读取（gitignore 已覆盖）。
        // 令牌绝不写入 BuildConfig（会被反编译提取）——公开仓库的 Release
        // 检测与附件下载均匿名可用，令牌仅发布脚本经环境变量使用。
        val localProps = Properties().apply {
            val f = rootProject.file("local.properties")
            if (f.exists()) f.inputStream().use { load(it) }
        }
        buildConfigField("String", "GITEE_OWNER", "\"${localProps.getProperty("gitee.owner", "")}\"")
        buildConfigField("String", "GITEE_REPO", "\"${localProps.getProperty("gitee.repo", "")}\"")
        buildConfigField("String", "GITHUB_OWNER", "\"NoraStory\"")
        buildConfigField("String", "GITHUB_REPO", "\"douyin-vocal-remover\"")
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation(libs.androidx.datastore.preferences)
    implementation("androidx.core:core-ktx:1.17.0")
}
