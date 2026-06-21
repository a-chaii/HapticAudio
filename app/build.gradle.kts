plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.hapticaudio"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.hapticaudio"
        minSdk = 26 // 建议至少 26，VibrationEffect 需要 O 以上
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
    // LSPosed API (只参与编译，不打包)
    compileOnly("de.robv.android.xposed:api:82")
    // Kotlin 协程，用于低延迟异步处理
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}
