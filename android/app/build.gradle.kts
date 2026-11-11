plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.vibetype"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.vibetype"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        // 侧载用的固定签名：本地和 CI 同一个 key，升级安装才不会签名冲突。
        // 这不是上架 Google Play 的密钥；真要上架时换新 key 并保密。
        create("release") {
            storeFile = file("vibetype-release.jks")
            storePassword = "vibetype2026"
            keyAlias = "vibetype"
            keyPassword = "vibetype2026"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlinOptions {
        jvmTarget = "21"
    }

    buildFeatures {
        compose = true
    }

    androidResources {
        // 模型是 228 MB 的 onnx，压缩没有意义，还会拖慢加载
        noCompress += "onnx"
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        // sherpa-onnx 的 JNI 库已经按 ABI 放好，不需要再压缩一遍
        jniLibs.useLegacyPackaging = false
    }
}

// 模型不进 git（GitHub 单文件限制 100 MB），构建前确认 assets 已就位
val checkSenseVoiceModel = tasks.register("checkSenseVoiceModel") {
    doLast {
        val dir = file("src/main/assets/models/sensevoice")
        if (!dir.resolve("model.int8.onnx").isFile || !dir.resolve("tokens.txt").isFile) {
            throw GradleException("缺少 SenseVoice 模型：先在仓库根目录运行 tools/fetch_model.sh")
        }
    }
}

tasks.named("preBuild").configure { dependsOn(checkSenseVoiceModel) }

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.06.01"))
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.1")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
}
