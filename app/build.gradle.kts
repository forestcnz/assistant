// app 模块：宿主 App（任务管理/编辑器/悬浮工具）+ 各 core 包
// AGP 9 使用内置 Kotlin 编译（无需独立 kotlin-android 插件）
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.forest.android.assistant"
    // core-ktx 1.19.1 与 compose-ui 1.12.1 的 aar-metadata 要求 minCompileSdk=37
    compileSdk = 37

    defaultConfig {
        applicationId = "com.forest.android.assistant"
        // 支持 Android 10+（API 29）。代码中 Process.waitFor(timeout)、Notification.Builder(channel)、
        // File.toPath() 等均要求 API 26+，与 24 不一致；定为 29 可避免低版本运行时崩溃。
        minSdk = 29
        targetSdk = 37
        testInstrumentationRunner = "com.forest.android.assistant.core.image.ImageApiInstrumentation"
        versionCode = providers.gradleProperty("appVersionCode").orElse("1").get().toInt()
        versionName = providers.gradleProperty("appVersionName").orElse("0.1.5").get()
    }

    // 正式签名密钥不入库（仓库是公开的）。CI 由 workflow 从 RELEASE_KEYSTORE_BASE64 解码成文件，
    // 再通过 RELEASE_KEYSTORE_PATH / RELEASE_KEYSTORE_PASSWORD / RELEASE_KEY_ALIAS / RELEASE_KEY_PASSWORD 传入；
    // 本地没有这些变量时回退到 debug 签名，保证任何环境都能产出可安装的 APK
    val releaseKeystorePath = providers.environmentVariable("RELEASE_KEYSTORE_PATH").orNull
    signingConfigs {
        if (!releaseKeystorePath.isNullOrBlank()) {
            create("release") {
                storeFile = file(releaseKeystorePath)
                storePassword = providers.environmentVariable("RELEASE_KEYSTORE_PASSWORD").orNull
                keyAlias = providers.environmentVariable("RELEASE_KEY_ALIAS").orNull
                keyPassword = providers.environmentVariable("RELEASE_KEY_PASSWORD").orNull
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = if (releaseKeystorePath.isNullOrBlank()) {
                signingConfigs.getByName("debug")
            } else {
                signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // 打包导出需要：native 库在安装时解压（否则重打包后对齐丢失会导致 INSTALL_FAILED_INVALID_APK）
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    buildFeatures {
        compose = true
    }

    sourceSets.getByName("main").assets.directories.add(layout.buildDirectory.dir("generated/speedAssets").get().asFile.absolutePath)
}

// Payload code is built independently so its DEX can be embedded in a selected APK.
// Network artifacts are pinned by SHA-256 in tooling/speed-runtime/payload-lock.json.
val speedTooling = rootProject.file("tooling/speed-runtime")
val speedAssets = layout.buildDirectory.dir("generated/speedAssets")
val installSpeedTools = tasks.register<Exec>("installSpeedTools") {
    workingDir(speedTooling)
    commandLine(if (System.getProperty("os.name").startsWith("Windows")) "npm.cmd" else "npm", "ci", "--no-audit", "--no-fund")
    inputs.files(speedTooling.resolve("package.json"), speedTooling.resolve("package-lock.json"))
    outputs.dir(speedTooling.resolve("node_modules"))
}
val prepareSpeedPayload = tasks.register<Exec>("prepareSpeedPayload") {
    dependsOn(":speed-runtime:assembleDebug", installSpeedTools)
    val runtimeApk = rootProject.file("speed-runtime/build/outputs/apk/debug/speed-runtime-debug.apk")
    inputs.file(runtimeApk)
    inputs.files(speedTooling.resolve("prepare.py"), speedTooling.resolve("payload-lock.json"), speedTooling.resolve("THIRD_PARTY_NOTICES.md"))
    inputs.files(speedTooling.resolve("FRIDA-COPYING.txt"), speedTooling.resolve("LGPL-2.0.txt"), speedTooling.resolve("package-lock.json"))
    outputs.dir(speedAssets.map { it.dir("speed-runtime/gadget") })
    outputs.file(speedAssets.map { it.file("speed-runtime/runtime.dex") })
    outputs.file(speedAssets.map { it.file("speed-runtime/THIRD_PARTY_NOTICES.md") })
    outputs.dir(speedAssets.map { it.dir("speed-runtime/licenses") })
    val python = providers.environmentVariable("SPEED_PYTHON").orElse(if (System.getProperty("os.name").startsWith("Windows")) "py" else "python3")
    commandLine(python.get(), speedTooling.resolve("prepare.py"), speedAssets.get().asFile, runtimeApk, rootProject.file("build/speed-downloads"))
}
val bundleSpeedHook = tasks.register<Exec>("bundleSpeedHook") {
    dependsOn(installSpeedTools)
    workingDir(speedTooling)
    inputs.files(speedTooling.resolve("hook.js"), speedTooling.resolve("clock.mjs"), speedTooling.resolve("build.mjs"), speedTooling.resolve("package-lock.json"))
    outputs.file(speedAssets.map { it.file("speed-runtime/hook.js") })
    commandLine("node", speedTooling.resolve("build.mjs"), speedAssets.get().file("speed-runtime/hook.js").asFile)
}
tasks.named("preBuild") { dependsOn(prepareSpeedPayload, bundleSpeedHook) }
tasks.withType<Test>().configureEach {
    dependsOn(":speed-runtime:assembleDebug")
    systemProperty("speed.fixture.apk", rootProject.file("speed-runtime/build/outputs/apk/debug/speed-runtime-debug.apk"))
    systemProperty("speed.android.sdk", providers.environmentVariable("ANDROID_HOME").orElse(providers.environmentVariable("ANDROID_SDK_ROOT")).orElse("").get())
}

dependencies {
    implementation("com.android.tools.build:apksig:9.4.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    // Compose BOM 统一管理 Compose 版本
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.11.0")
    implementation("androidx.activity:activity-compose:1.13.0")

    // QuickJS 脚本引擎（quickjs-kt，ES2020+，含各 ABI 原生库）
    // 1.0.7 起支持 interruptEvaluation()，可中断纯 JS 忙循环；1.0.9 起 close 也会中断正在执行的求值；
    // 1.0.14 修复 binding 异常传播（exit()/停止异常必须能抛出）；1.0.15 修复 JNI 字符串 Unicode/emoji。
    // 该库为 Kotlin 2.4 元数据编译，需配合 Kotlin 2.4.x 编译器（见根 build.gradle.kts 的 compose 插件版本）。
    implementation("io.github.dokar3:quickjs-kt:1.0.15")
    // 与 quickjs-kt 传递的 coroutines-core 1.11.0 对齐，避免 android/core 版本不一致
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    // 定时任务（WorkManager 到点拉起任务）
    implementation("androidx.work:work-runtime-ktx:2.12.0")

    // OCR：MLKit 文字识别（中文模型内置，约 10MB；设计文档的备选方案，避免 PaddleOCR 15-20MB 模型）
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")

    // 局域网浏览器编辑器：轻量嵌入式 HTTP 服务
    implementation("org.nanohttpd:nanohttpd:2.3.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
