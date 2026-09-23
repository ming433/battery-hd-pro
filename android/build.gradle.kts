// 根构建脚本：只声明插件版本，子模块按需 apply。
// 版本集中在此，避免 analytics-sdk 与 app 的 AGP/Kotlin 版本漂移。
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("com.android.library") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
