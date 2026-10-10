import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.agp.app)
    alias(libs.plugins.kotlin)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.composeCompiler)
    id("kotlin-parcelize")
}

// 发布签名密钥库。*.jks 在 .gitignore 里，克隆仓库后该文件默认不存在；
// 构建脚本据此回退到 debug 签名，保证 assembleRelease 仍能产出可安装的 APK。
val releaseKeystore = file("signing/magicorig.jks")

android {
    namespace = "com.redwind.magicorig"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.redwind.magicorig"
        minSdk = 34   // Android 14 (MagicOS 8 / HarmonyOS NEXT)
        targetSdk = 35
        versionCode = 100007
        versionName = "1.0.6"
    }

    signingConfigs {
        create("release") {
            storeFile = releaseKeystore
            storePassword = "magicorig123"
            keyAlias = "magicorig"
            keyPassword = "magicorig123"
        }
    }

    buildTypes {
        debug {
            isDebuggable = true
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            multiDexEnabled = true
            // 密钥库缺失时回退到 debug 签名，避免 assembleRelease 直接失败
            signingConfig = if (releaseKeystore.exists()) {
                signingConfigs.getByName("release")
            } else {
                println("MagicOriG: 未找到 $releaseKeystore，assembleRelease 将使用 debug 签名")
                signingConfigs.getByName("debug")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    dependenciesInfo.includeInApk = false

    java {
        toolchain {
            languageVersion = JavaLanguageVersion.of(JavaVersion.VERSION_21.majorVersion)
        }
    }

    kotlin {
        jvmToolchain(JavaVersion.VERSION_21.majorVersion.toInt())
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    tasks.withType<com.android.build.gradle.internal.tasks.CheckAarMetadataTask>().configureEach {
        enabled = false
    }

    // LSPosed 现代模块声明（META-INF/xposed/{module.prop,java_init.list,scope.list}）
    // 必须作为「Java resources」打进 APK 根目录，LSPosed 靠扫描 APK zip 条目识别模块。
    // AGP 的默认 sourceSet 已包含 src/main/resources，此处不要删除该目录。

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/**.version"
            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
            excludes += "okhttp3/**"
            excludes += "kotlin/**"
            excludes += "org/**"
            excludes += "**.properties"
            excludes += "**.bin"
            excludes += "kotlin-tooling-metadata.json"
            // 上面的 excludes 会作用于所有 Java resources，务必别把 LSPosed 声明也干掉：
            // 校验任务 verifyXposedModule 会在打包后兜底检查 META-INF/xposed/**
        }
    }
}

/**
 * 打包后校验 LSPosed 现代模块声明。
 *
 * 背景：LSPosed 判断「这是不是模块」只看 APK zip 里有没有
 * META-INF/xposed/java_init.list（见 ModuleUtil.getModernModuleApk）。
 * 该文件一旦被 AGP 的 packaging 规则或 sourceSet 配置吞掉，模块就会
 * 从 LSPosed 列表里彻底消失，而且构建仍然「成功」——所以这里强制校验。
 */
val verifyXposedModule = tasks.register("verifyXposedModule") {
    group = "verification"
    description = "校验 APK 内 META-INF/xposed 模块声明是否完整，防止 LSPosed 识别不到模块"

    val apkDirectory = layout.buildDirectory.dir("outputs/apk")

    doLast {
        val dir = apkDirectory.get().asFile
        val apks = if (dir.exists()) {
            dir.walkTopDown().filter { it.isFile && it.extension == "apk" }.toList()
        } else emptyList()

        if (apks.isEmpty()) {
            logger.warn("verifyXposedModule: $dir 下没有 APK，跳过校验")
            return@doLast
        }

        var failed = false
        for (apk in apks) {
            logger.lifecycle("verifyXposedModule: 检查 ${apk.name}")
            try {
                ZipFile(apk).use { zip ->
                    val javaInit = zip.getEntry("META-INF/xposed/java_init.list")
                    if (javaInit == null) {
                        failed = true
                        logger.error("  [FAIL] 缺少 META-INF/xposed/java_init.list —— LSPosed 不会把它识别为模块")
                    } else {
                        val entries = zip.getInputStream(javaInit).bufferedReader()
                            .readLines().map { it.trim() }.filter { it.isNotEmpty() }
                        if (entries.isEmpty()) {
                            failed = true
                            logger.error("  [FAIL] META-INF/xposed/java_init.list 为空")
                        } else {
                            entries.forEach { logger.lifecycle("  [OK] 入口类: $it") }
                        }
                    }

                    val prop = zip.getEntry("META-INF/xposed/module.prop")
                    if (prop == null) {
                        failed = true
                        logger.error("  [FAIL] 缺少 META-INF/xposed/module.prop")
                    } else {
                        val map = zip.getInputStream(prop).bufferedReader().readLines()
                            .map { it.trim() }
                            .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains('=') }
                            .associate { line ->
                                val idx = line.indexOf('=')
                                line.substring(0, idx).trim() to line.substring(idx + 1).trim()
                            }
                        logger.lifecycle("  [OK] module.prop: $map")
                        if (!map.containsKey("minApiVersion")) {
                            failed = true
                            logger.error("  [FAIL] module.prop 缺少 minApiVersion")
                        }
                        if (!map.containsKey("targetApiVersion")) {
                            // LSPosed: targetVersion = extractIntPart(prop.getProperty("targetApiVersion"))
                            logger.warn("  [WARN] module.prop 缺少 targetApiVersion（建议显式声明）")
                        }
                        if (!map.containsKey("staticScope")) {
                            logger.warn("  [WARN] module.prop 缺少 staticScope（默认 false = 作用域可被用户改）")
                        }
                    }

                    val scope = zip.getEntry("META-INF/xposed/scope.list")
                    if (scope == null) {
                        failed = true
                        logger.error("  [FAIL] 缺少 META-INF/xposed/scope.list —— 现代模块作用域将为空")
                    } else {
                        val scopes = zip.getInputStream(scope).bufferedReader().readLines()
                            .map { it.trim() }.filter { it.isNotEmpty() }
                        if (scopes.isEmpty()) {
                            failed = true
                            logger.error("  [FAIL] META-INF/xposed/scope.list 为空")
                        } else {
                            scopes.forEach { logger.lifecycle("  [OK] 作用域: $it") }
                        }
                    }
                }
            } catch (error: Throwable) {
                failed = true
                logger.error("  [FAIL] 读取 ${apk.name} 失败: ${error.message}")
            }
        }

        if (failed) {
            throw GradleException(
                "verifyXposedModule 失败：APK 内的 LSPosed 模块声明不完整，" +
                    "构建出的 APK 在 LSPosed 模块列表里不会出现。"
            )
        }
        logger.lifecycle("verifyXposedModule: 全部通过")
    }
}

// 每次打包完成后自动校验
tasks.matching { it.name.startsWith("assemble") }.configureEach {
    finalizedBy(verifyXposedModule)
}

configurations.configureEach {
    exclude(group = "androidx.lifecycle", module = "lifecycle-viewmodel-ktx")
    // Force Kotlin stdlib to match our compiler version
    resolutionStrategy.eachDependency {
        if (requested.group == "org.jetbrains.kotlin" && requested.name.startsWith("kotlin-stdlib")) {
            useVersion("2.0.21")
            because("Must match Kotlin compiler version")
        }
    }
}

dependencies {
    // AndroidX
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.5")

    // LSPosed / LibXposed
    compileOnly("io.github.libxposed:api:102.0.0")
    implementation("io.github.libxposed:service:101.0.0")

    // Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Compose BOM
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.ui:ui:1.7.2")
    implementation("androidx.compose.foundation:foundation:1.7.2")
    implementation("androidx.compose.ui:ui-tooling-preview:1.7.2")
    debugImplementation("androidx.compose.ui:ui-tooling:1.7.2")
    implementation("androidx.compose.material3:material3:1.3.0")

    // Miuix UI —— 实测不可用：miuix 0.9.x 的 kotlin metadata = 2.4.0，需 Kotlin 2.4.10；
    // 但升 Kotlin 2.4.10 后 Gradle 8.9 又报 kotlin.concurrent.atomics.AtomicsKt 缺失
    // （需 Gradle 9 + AGP 9，连带升级有破坏现有可用模块的风险）。详见 docs/magicos11-integration.md
    // implementation("top.yukonga.miuix.kmp:miuix-ui-android:0.9.3")
    // implementation("top.yukonga.miuix.kmp:miuix-preference-android:0.9.3")
    // implementation("top.yukonga.miuix.kmp:miuix-blur-android:0.9.3")
    // implementation("top.yukonga.miuix.kmp:miuix-icons-android:0.9.3")
    // implementation("top.yukonga.miuix.kmp:miuix-navigation3-ui-android:0.9.3")
    implementation("androidx.navigation3:navigation3-runtime:1.1.4")
}
