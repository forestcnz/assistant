// 根构建脚本：统一管理插件版本
// 组合：Gradle 9.6.1（本地缓存）+ AGP 9.0.0 built-in Kotlin + Compose 编译器插件
plugins {
    id("com.android.application") version "9.0.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.20" apply false
}
