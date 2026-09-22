// 顶层构建文件：只声明插件版本，具体配置在 app/build.gradle.kts
// AGP 9 起内置 Kotlin 支持，不再需要（也不能）单独应用 org.jetbrains.kotlin.android
plugins {
    id("com.android.application") version "9.4.1" apply false
}
