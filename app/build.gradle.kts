plugins {
    // AGP 9 起内置 Kotlin 支持：只需要这一个插件，不要再加 org.jetbrains.kotlin.android
    id("com.android.application")
}

android {
    namespace = "com.example.taitoulv"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.example.taitoulv"
        minSdk = 23
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
    }
}

// 内置 Kotlin 的编译选项（替代已移除的 android.kotlinOptions）
kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("com.google.android.material:material:1.14.0")

    val cameraxVersion = "1.6.2"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")
    // CameraX 官方的 ML Kit 集成：负责把 ML Kit 结果换算到 PreviewView 坐标系
    implementation("androidx.camera:camera-mlkit-vision:$cameraxVersion")

    // 人脸检测：模型直接打包进 APK，运行时不联网、不上传任何画面
    implementation("com.google.mlkit:face-detection:16.1.7")

    testImplementation("junit:junit:4.13.2")
}
