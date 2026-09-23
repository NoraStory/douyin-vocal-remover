import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Release 签名：读取 android/keystore.password（单行明文），keystore 位于 android/release.keystore。
// 两者均已 gitignore，不随仓库分发。
val keystorePassword: String? = rootProject.file("keystore.password")
    .takeIf { it.exists() }
    ?.readText()
    ?.trim()
    ?.takeIf { it.isNotEmpty() }

android {
    namespace = "com.nora.douyinremover"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.nora.douyinremover"
        minSdk = 26
        targetSdk = 37
        versionCode = 10
        versionName = "1.4.1"

        ndk {
            // ffmpeg-kit-maintained 8.x 原生库仅提供 arm64-v8a 与 x86_64（模拟器），
            // abiFilters 与其保持一致，避免 32 位设备运行时缺 .so 崩溃。
            abiFilters += listOf("arm64-v8a", "x86_64")
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (keystorePassword != null) {
                signingConfig = signingConfigs.create("release") {
                    storeFile = rootProject.file("release.keystore")
                    storePassword = keystorePassword
                    keyAlias = "douyinremover"
                    keyPassword = keystorePassword
                }
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":douyin"))
    implementation(project(":audio-engine"))
    implementation(project(":settings"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
}
