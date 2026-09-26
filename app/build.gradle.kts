plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ============================================================
// 版本管理：每次功能更新时递增 versionName 与 versionCode。
// APK 输出文件名会自动带上版本号，例如：
//   DeepSeekLocalAPI-v1.1.0-release.apk
// ============================================================
val appVersionCode = 20       // 整数版本号，必须单调递增
val appVersionName = "2.0.0"  // 语义化版本：主版本.功能版本.修复版本

// ============================================================
// 统一签名：为保证「覆盖安装不丢数据」，所有版本必须用同一把 key 签名。
// 项目内固化了一把专用 keystore（app/deepseek-release.keystore），
// 不要删除、不要更换、务必备份。一旦换 key，旧版本的覆盖安装会因
// 签名不一致而失败（数据无法无缝保留）。
// 如要隐私保护，可把密码移到 ~/.gradle/gradle.properties 或环境变量，
// 但 keystore 文件本身务必保留在同一位置。
//
// 说明：git 托管平台不便直接入库二进制文件，仓库中以
// app/deepseek-release.keystore.b64（base64 文本）为准；
// 首次构建时下方逻辑会自动还原出 app/deepseek-release.keystore。
// ============================================================
val keystoreFile = rootProject.file("app/deepseek-release.keystore")
val keystoreB64 = rootProject.file("app/deepseek-release.keystore.b64")
if (!keystoreFile.exists() && keystoreB64.exists()) {
    keystoreFile.writeBytes(java.util.Base64.getDecoder().decode(keystoreB64.readText().trim()))
}
val releaseStorePassword = "DeepSeekLocal_API_2026"
val releaseKeyAlias = "deepseek"
val releaseKeyPassword = "DeepSeekLocal_API_2026"

android {
    namespace = "com.ds.localapi"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.ds.localapi"
        minSdk = 29
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersionName
    }

    signingConfigs {
        create("release") {
            storeFile = keystoreFile
            storePassword = releaseStorePassword
            keyAlias = releaseKeyAlias
            keyPassword = releaseKeyPassword
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
        // debug 也统一用同一把 key 签名，避免「今天装的 debug 下个版本对不上」。
        // 这样无论是 release 还是 debug，安装升级时签名都一致。
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }

    // 自定义 APK 输出文件名，带上版本号，让人一眼看出是哪个版本
    applicationVariants.all {
        outputs.all {
            val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            output.outputFileName =
                "DeepSeekLocalAPI-v${appVersionName}-${buildType.name}.apk"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // DeepSeek HTTP/SSE client
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Local OpenAI-compatible HTTP server
    implementation("org.nanohttpd:nanohttpd:2.3.1")
}