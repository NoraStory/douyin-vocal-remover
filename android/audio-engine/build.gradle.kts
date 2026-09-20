plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.nora.douyinremover.audio"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
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
    implementation(libs.onnxruntime.android)
    implementation(libs.ffmpeg.kit.audio)
    implementation(libs.kotlinx.coroutines.android)
}
