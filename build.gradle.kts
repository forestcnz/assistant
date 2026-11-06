// 根构建脚本：统一管理插件版本
// 组合：Gradle 9.8.2 + AGP 9.4.1 built-in Kotlin + Compose 编译器插件 2.4.21
// compose 编译器插件版本同时决定内置 Kotlin 版本；quickjs-kt 1.0.15 为 Kotlin 2.4 元数据，
// 因此这里必须 ≥ 2.4.0
plugins {
    id("com.android.application") version "9.4.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
}
