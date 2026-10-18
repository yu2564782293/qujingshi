plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.qujingshi.aicamera"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.qujingshi.aicamera"
        // minSdk 29：MediaStore RELATIVE_PATH / loadThumbnail 都是 API 29 起，
        // 相簿存取才能不要任何存储权限
        minSdk = 29
        targetSdk = 35
        versionCode = 11
        versionName = "0.7.0"

        // MediaPipe 原生库很肥，只留 arm64（近年 Android 手机都是）
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.3")

    val camerax = "1.4.2"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")

    // 姿势侦测（on-device，模型档在 assets/pose_landmarker_lite.task）
    implementation("com.google.mediapipe:tasks-vision:0.10.14")

    // NIMA 美学评分模型（assets/nima_aesthetic.tflite）
    implementation("org.tensorflow:tensorflow-lite:2.14.0")
}
