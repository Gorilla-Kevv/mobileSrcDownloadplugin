import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// 签名配置：signing.properties 存在（含 keystore 路径与密码，不入 git）时启用正式签名
val signingProps = Properties().apply {
    val f = rootProject.file("signing.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

// 版本号唯一来源：根目录 version.properties（发布脚本 scripts/publish-release.sh 自增它）
val versionProps = Properties().apply {
    val f = rootProject.file("version.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val appVersionCode = versionProps.getProperty("versionCode", "1").trim().toInt()
val appVersionName = versionProps.getProperty("versionName", "1.0.0").trim()

// 应用内更新通道：默认按公开发布仓库拼 GitHub Release 固定链接；
// 可用 clipdown.updateManifestUrl / clipdown.updateApkUrl 指向任意托管（换国内源不改代码）
val updateRepo = (project.findProperty("clipdown.updateRepo") as String?)
    ?: "Gorilla-Kevv/mobileSrcDownloadplugin"
val apkAssetName = (project.findProperty("clipdown.apkAssetName") as String?)
    ?: "clipdown-release.apk"
val updateManifestUrl = (project.findProperty("clipdown.updateManifestUrl") as String?)
    ?.takeIf { it.isNotBlank() }
    ?: "https://github.com/$updateRepo/releases/latest/download/update.json"
val updateApkUrl = (project.findProperty("clipdown.updateApkUrl") as String?)
    ?.takeIf { it.isNotBlank() }
    ?: "https://github.com/$updateRepo/releases/latest/download/$apkAssetName"

android {
    namespace = "com.clipdown.app"
    compileSdk = 34

    signingConfigs {
        create("release") {
            if (signingProps.isNotEmpty()) {
                storeFile = rootProject.file(signingProps.getProperty("storeFile"))
                storePassword = signingProps.getProperty("storePassword")
                keyAlias = signingProps.getProperty("keyAlias")
                keyPassword = signingProps.getProperty("keyPassword")
            }
        }
    }

    defaultConfig {
        applicationId = "com.clipdown.app"
        minSdk = 26
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        resourceConfigurations += setOf("zh", "en")

        // 应用内更新所需常量（改发布仓库/换托管只需改 gradle.properties，不必动代码）
        buildConfigField("String", "UPDATE_REPO", "\"$updateRepo\"")
        buildConfigField("String", "UPDATE_MANIFEST_URL", "\"$updateManifestUrl\"")
        buildConfigField("String", "UPDATE_APK_URL", "\"$updateApkUrl\"")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi"
        )
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 签名：signing.properties 存在时用正式签名（keystore 不入 git），否则落回 debug 签名保证可装
            if (rootProject.file("signing.properties").exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
}

dependencies {
    implementation(project(":parser"))
    implementation(project(":downloader"))

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-service:2.8.6")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.navigation:navigation-compose:2.8.3")
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // 悬浮窗内嵌 Compose 需要手动提供 Lifecycle / SavedState 宿主
    implementation("androidx.lifecycle:lifecycle-runtime-android:2.8.6")
    implementation("androidx.savedstate:savedstate:1.2.1")

    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")
    implementation("io.coil-kt:coil-compose:2.7.0")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
}
