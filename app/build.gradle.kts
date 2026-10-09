plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.lanbridge"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.lanbridge"
        minSdk = 28
        targetSdk = 34
        versionCode = 2
        versionName = "1.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
    buildFeatures {
        viewBinding = false
    }
    packaging {
        resources.excludes += "META-INF/*"
    }
}

dependencies {
    // Ktor 内嵌服务（CIO 引擎，HTTP + WS 同端口）
    implementation("io.ktor:ktor-server-core:2.3.10")
    implementation("io.ktor:ktor-server-cio:2.3.10")
    implementation("io.ktor:ktor-server-websockets:2.3.10")
    // 图片加载（系统缩略图，零原图解码）
    implementation("com.github.bumptech.glide:glide:4.16.0")
    // 协程
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    // QR 本地生成
    implementation("com.google.zxing:core:3.5.3")
    // AndroidX 基础
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("com.google.android.material:material:1.11.0")
    // WS JSON 手工解析，无需序列化框架
}
