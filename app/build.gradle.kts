import com.android.build.api.dsl.ApplicationExtension
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.baselineprofile)
}

android {
    namespace = "com.molagpt.app"
    compileSdk = 36

    val releaseSigningFile = rootProject.file("signing/release.properties")
    val releaseSigning = Properties()
    if (releaseSigningFile.isFile) {
        releaseSigningFile.inputStream().use { releaseSigning.load(it) }
    }

    fun releaseSigningValue(name: String): String? =
        releaseSigning.getProperty(name)?.takeIf { it.isNotBlank() }

    val hasReleaseSigning = listOf(
        "storeFile",
        "storePassword",
        "keyAlias",
        "keyPassword",
    ).all { releaseSigningValue(it) != null }

    defaultConfig {
        applicationId = "com.molagpt.app"
        minSdk = 23
        targetSdk = 36
        versionCode = 13
        versionName = "0.3.9"
        // 开发专用构建时间戳：注入 BuildConfig.BUILD_TIME，设置页底部展示。
        // configuration-cache 已关（见 gradle.properties），每次构建都会重新求值 → 时间会变，
        // 据此可确认装到机器上的是不是刚编译的新包。
        buildConfigField(
            "String",
            "BUILD_TIME",
            "\"${SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date())}\"",
        )
        // 当前仅打包 arm64-v8a，减少首版安装包体积；后续引入 native 依赖时再扩展 ABI。
        ndk { abiFilters += "arm64-v8a" }
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(releaseSigningValue("storeFile")!!)
                storePassword = releaseSigningValue("storePassword")
                keyAlias = releaseSigningValue("keyAlias")
                keyPassword = releaseSigningValue("keyPassword")
            }
        }
    }

    buildTypes {
        getByName("debug") {
            isMinifyEnabled = false
        }
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/INDEX.LIST",
            "/META-INF/io.netty.versions.properties",
        )
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // core
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":core:network"))
    implementation(project(":core:storage"))
    implementation(project(":core:render"))
    implementation(project(":core:markdown"))
    // feature
    implementation(project(":feature:chat"))
    implementation(project(":feature:agent-control"))
    implementation(project(":feature:session"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:auth"))
    implementation(project(":feature:webview"))
    implementation(project(":feature:file"))
    implementation(project(":feature:share"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.bundles.compose)
    implementation(libs.androidx.profileinstaller)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // baseline profile（由 :baselineprofile 模块生成后合入）
    baselineProfile(project(":baselineprofile"))
}
