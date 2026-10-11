plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// 高德 Key 从 local.properties 读取（该文件本就不进 VCS），也支持环境变量兜底。
// 注意：改 Key 后需在 Android Studio 里 Re-Sync（configuration-cache 不追踪此处文件读取）。
val amapKeys: Map<String, String> = run {
    val f = rootProject.file("local.properties")
    if (!f.exists()) {
        emptyMap()
    } else {
        f.readText()
            .lineSequence()
            .filter { line -> line.contains("=") && !line.trim().startsWith("#") }
            .associate { line ->
                val idx = line.indexOf('=')
                line.substring(0, idx).trim() to line.substring(idx + 1).trim()
            }
    }
}

fun amapProp(key: String): String =
    amapKeys[key]?.takeIf { value -> value.isNotBlank() } ?: (System.getenv(key)?.trim() ?: "")

android {
    namespace = "com.example.myapplication"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.myapplication"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 高德 Key：未配置时注入占位符，UI 依据 AMAP_KEY_CONFIGURED 降级（不黑屏）
        manifestPlaceholders["AMAP_KEY"] = "REPLACE_WITH_YOUR_AMAP_KEY"
        buildConfigField("boolean", "AMAP_KEY_CONFIGURED", "false")
    }

    buildTypes {
        debug {
            // debug 保留全部 ABI（模拟器 x86_64 需要出图）
            val k = amapProp("AMAP_KEY_DEBUG").ifBlank { amapProp("AMAP_KEY") }
            manifestPlaceholders["AMAP_KEY"] = k.ifBlank { "REPLACE_WITH_YOUR_AMAP_KEY" }
            buildConfigField("boolean", "AMAP_KEY_CONFIGURED", k.isNotBlank().toString())
        }
        release {
            val k = amapProp("AMAP_KEY_RELEASE").ifBlank { amapProp("AMAP_KEY") }
            manifestPlaceholders["AMAP_KEY"] = k.ifBlank { "REPLACE_WITH_YOUR_AMAP_KEY" }
            buildConfigField("boolean", "AMAP_KEY_CONFIGURED", k.isNotBlank().toString())
            // 高德 so 体积大，release 只保留真机 ABI
            ndk {
                abiFilters += listOf("armeabi-v7a", "arm64-v8a")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.adaptive.navigation.suite)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    // 高德 3D 地图 SDK 已内置定位 API（com.amap.api.location.*），
    // 不要再单独引 com.amap.api:location，否则两类重复导致 checkDuplicateClasses 失败。
    implementation(libs.amap.map3d)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
