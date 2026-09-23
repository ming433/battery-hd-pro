plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.batteryhd.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.batteryhd.app"
        minSdk = 26          // Android 8.0+：东南亚主流机型下限，也是 AdMob 最低要求
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        // 面向东南亚，默认印尼（PRD：locale=in）
        resourceConfigurations += setOf("in", "en")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            // debug 包允许明文流量，便于连本地/测试服
            buildConfigField("boolean", "USE_PROD_API", "false")
            buildConfigField("String", "API_BASE_URL", "\"http://39.106.113.189:8082/api/v1\"")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            buildConfigField("boolean", "USE_PROD_API", "true")
            buildConfigField("String", "API_BASE_URL", "\"https://api.batteryhd.pro/api/v1\"")
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/DEPENDENCIES",
            "META-INF/LICENSE*",
        )
    }
}

dependencies {
    implementation(project(":analytics-sdk"))

    // ---- AndroidX
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.fragment:fragment-ktx:1.8.4")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-process:2.8.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.google.android.material:material:1.12.0")

    // ---- 异步
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // ---- 变现：AdMob
    implementation("com.google.android.gms:play-services-ads:23.6.0")

    // ---- 变现：Play 订阅（客户端只做行为漏斗，收入真相源是服务端 RTDN）
    implementation("com.android.billingclient:billing:7.1.1")
    implementation("com.android.billingclient:billing-ktx:7.1.1")
}
