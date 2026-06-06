plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.molagpt.app.core.render"
    compileSdk = 36

    defaultConfig {
        minSdk = 23
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":core:model"))
    api(project(":core:markdown"))
    implementation(project(":core:common"))

    // Compose 版本由 libs.versions.toml 集中管理。
    implementation(libs.bundles.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.core.ktx) // createBitmap 扩展
    implementation("androidx.collection:collection-ktx:1.4.5") // LruCache

    // LaTeX：JLaTeXMath Android 版，使用原生 Canvas 渲染。
    implementation(libs.jlatexmath.android)
}
