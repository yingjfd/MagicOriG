import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.agp.app)
    alias(libs.plugins.kotlin)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.composeCompiler)
    id("kotlin-parcelize")
}

// 鍙戝竷绛惧悕瀵嗛挜搴撱€?.jks 鍦?.gitignore 閲岋紝鍏嬮殕浠撳簱鍚庤鏂囦欢榛樿涓嶅瓨鍦紱
// 鏋勫缓鑴氭湰鎹鍥為€€鍒?debug 绛惧悕锛屼繚璇?assembleRelease 浠嶈兘浜у嚭鍙畨瑁呯殑 APK銆?
val releaseKeystore = file("signing/magicorig.jks")

android {
    namespace = "com.redwind.magicorig"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.redwind.magicorig"
        minSdk = 34   // Android 14 (MagicOS 8 / HarmonyOS NEXT)
        targetSdk = 35
        versionCode = 100003
        versionName = "1.0.2"
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
            // 瀵嗛挜搴撶己澶辨椂鍥為€€鍒?debug 绛惧悕锛岄伩鍏?assembleRelease 鐩存帴澶辫触
            signingConfig = if (releaseKeystore.exists()) {
                signingConfigs.getByName("release")
            } else {
                println("MagicOriG: 鏈壘鍒?$releaseKeystore锛宎ssembleRelease 灏嗕娇鐢?debug 绛惧悕")
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

    // LSPosed 鐜颁唬妯″潡澹版槑锛圡ETA-INF/xposed/{module.prop,java_init.list,scope.list}锛?
    // 蹇呴』浣滀负銆孞ava resources銆嶆墦杩?APK 鏍圭洰褰曪紝LSPosed 闈犳壂鎻?APK zip 鏉＄洰璇嗗埆妯″潡銆?
    // AGP 鐨勯粯璁?sourceSet 宸插寘鍚?src/main/resources锛屾澶勪笉瑕佸垹闄よ鐩綍銆?

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
            // 涓婇潰鐨?excludes 浼氫綔鐢ㄤ簬鎵€鏈?Java resources锛屽姟蹇呭埆鎶?LSPosed 澹版槑涔熷共鎺夛細
            // 鏍￠獙浠诲姟 verifyXposedModule 浼氬湪鎵撳寘鍚庡厹搴曟鏌?META-INF/xposed/**
        }
    }
}

/**
 * 鎵撳寘鍚庢牎楠?LSPosed 鐜颁唬妯″潡澹版槑銆?
 *
 * 鑳屾櫙锛歀SPosed 鍒ゆ柇銆岃繖鏄笉鏄ā鍧椼€嶅彧鐪?APK zip 閲屾湁娌℃湁
 * META-INF/xposed/java_init.list锛堣 ModuleUtil.getModernModuleApk锛夈€?
 * 璇ユ枃浠朵竴鏃﹁ AGP 鐨?packaging 瑙勫垯鎴?sourceSet 閰嶇疆鍚炴帀锛屾ā鍧楀氨浼?
 * 浠?LSPosed 鍒楄〃閲屽交搴曟秷澶憋紝鑰屼笖鏋勫缓浠嶇劧銆屾垚鍔熴€嶁€斺€旀墍浠ヨ繖閲屽己鍒舵牎楠屻€?
 */
val verifyXposedModule = tasks.register("verifyXposedModule") {
    group = "verification"
    description = "鏍￠獙 APK 鍐?META-INF/xposed 妯″潡澹版槑鏄惁瀹屾暣锛岄槻姝?LSPosed 璇嗗埆涓嶅埌妯″潡"

    val apkDirectory = layout.buildDirectory.dir("outputs/apk")

    doLast {
        val dir = apkDirectory.get().asFile
        val apks = if (dir.exists()) {
            dir.walkTopDown().filter { it.isFile && it.extension == "apk" }.toList()
        } else emptyList()

        if (apks.isEmpty()) {
            logger.warn("verifyXposedModule: $dir 涓嬫病鏈?APK锛岃烦杩囨牎楠?)
            return@doLast
        }

        var failed = false
        for (apk in apks) {
            logger.lifecycle("verifyXposedModule: 妫€鏌?${apk.name}")
            try {
                ZipFile(apk).use { zip ->
                    val javaInit = zip.getEntry("META-INF/xposed/java_init.list")
                    if (javaInit == null) {
                        failed = true
                        logger.error("  [FAIL] 缂哄皯 META-INF/xposed/java_init.list 鈥斺€?LSPosed 涓嶄細鎶婂畠璇嗗埆涓烘ā鍧?)
                    } else {
                        val entries = zip.getInputStream(javaInit).bufferedReader()
                            .readLines().map { it.trim() }.filter { it.isNotEmpty() }
                        if (entries.isEmpty()) {
                            failed = true
                            logger.error("  [FAIL] META-INF/xposed/java_init.list 涓虹┖")
                        } else {
                            entries.forEach { logger.lifecycle("  [OK] 鍏ュ彛绫? $it") }
                        }
                    }

                    val prop = zip.getEntry("META-INF/xposed/module.prop")
                    if (prop == null) {
                        failed = true
                        logger.error("  [FAIL] 缂哄皯 META-INF/xposed/module.prop")
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
                            logger.error("  [FAIL] module.prop 缂哄皯 minApiVersion")
                        }
                        if (!map.containsKey("targetApiVersion")) {
                            // LSPosed: targetVersion = extractIntPart(prop.getProperty("targetApiVersion"))
                            logger.warn("  [WARN] module.prop 缂哄皯 targetApiVersion锛堝缓璁樉寮忓０鏄庯級")
                        }
                        if (!map.containsKey("staticScope")) {
                            logger.warn("  [WARN] module.prop 缂哄皯 staticScope锛堥粯璁?false = 浣滅敤鍩熷彲琚敤鎴锋敼锛?)
                        }
                    }

                    val scope = zip.getEntry("META-INF/xposed/scope.list")
                    if (scope == null) {
                        failed = true
                        logger.error("  [FAIL] 缂哄皯 META-INF/xposed/scope.list 鈥斺€?鐜颁唬妯″潡浣滅敤鍩熷皢涓虹┖")
                    } else {
                        val scopes = zip.getInputStream(scope).bufferedReader().readLines()
                            .map { it.trim() }.filter { it.isNotEmpty() }
                        if (scopes.isEmpty()) {
                            failed = true
                            logger.error("  [FAIL] META-INF/xposed/scope.list 涓虹┖")
                        } else {
                            scopes.forEach { logger.lifecycle("  [OK] 浣滅敤鍩? $it") }
                        }
                    }
                }
            } catch (error: Throwable) {
                failed = true
                logger.error("  [FAIL] 璇诲彇 ${apk.name} 澶辫触: ${error.message}")
            }
        }

        if (failed) {
            throw GradleException(
                "verifyXposedModule 澶辫触锛欰PK 鍐呯殑 LSPosed 妯″潡澹版槑涓嶅畬鏁达紝" +
                    "鏋勫缓鍑虹殑 APK 鍦?LSPosed 妯″潡鍒楄〃閲屼笉浼氬嚭鐜般€?
            )
        }
        logger.lifecycle("verifyXposedModule: 鍏ㄩ儴閫氳繃")
    }
}

// 姣忔鎵撳寘瀹屾垚鍚庤嚜鍔ㄦ牎楠?
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

    // Miuix UI 鈥斺€?瀹炴祴涓嶅彲鐢細miuix 0.9.x 鐨?kotlin metadata = 2.4.0锛岄渶 Kotlin 2.4.10锛?
    // 浣嗗崌 Kotlin 2.4.10 鍚?Gradle 8.9 鍙堟姤 kotlin.concurrent.atomics.AtomicsKt 缂哄け
    // 锛堥渶 Gradle 9 + AGP 9锛岃繛甯﹀崌绾ф湁鐮村潖鐜版湁鍙敤妯″潡鐨勯闄╋級銆傝瑙?docs/magicos11-integration.md
    // implementation("top.yukonga.miuix.kmp:miuix-ui-android:0.9.3")
    // implementation("top.yukonga.miuix.kmp:miuix-preference-android:0.9.3")
    // implementation("top.yukonga.miuix.kmp:miuix-blur-android:0.9.3")
    // implementation("top.yukonga.miuix.kmp:miuix-icons-android:0.9.3")
    // implementation("top.yukonga.miuix.kmp:miuix-navigation3-ui-android:0.9.3")
    implementation("androidx.navigation3:navigation3-runtime:1.1.4")
}
