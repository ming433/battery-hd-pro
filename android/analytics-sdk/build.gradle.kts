plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.batteryhd.analytics"
    compileSdk = 34

    defaultConfig {
        // Android 8.0+：覆盖东南亚主流机型，且满足 AdMob 最低要求
        minSdk = 26
        targetSdk = 34

        consumerProguardFiles("consumer-rules.pro")
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
}

dependencies {
    // 协程：SDK 全部异步逻辑的基础（落盘、上报、配置拉取）
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // 生命周期：监听前后台切换以触发 flush 与新会话
    implementation("androidx.lifecycle:lifecycle-process:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")

    /**
     * 刻意不引入 OkHttp / Retrofit / Gson：
     * - 网络层用 HttpURLConnection 自行实现，避免与主 App 的网络库版本冲突
     * - JSON 用 org.json（Android 内置），零额外体积
     * SDK 定位是"轻量"，PRD 10.3.5 要求 CPU 占用 ≤ 1%、内存队列 ≤ 200KB。
     */
}
