plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.baselineprofile)
}

android {
    namespace = "com.molagpt.app.baselineprofile"
    compileSdk = 36

    defaultConfig {
        minSdk = 28 // baseline profile 生成要求 minSdk 28+（运行时安装仍覆盖到 app 的 minSdk 23）
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // 指向被测的 :app
    targetProjectPath = ":app"
}

kotlin {
    jvmToolchain(17)
}

// 用连接的设备/模拟器生成（CI 可配 managed device）。
baselineProfile {
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.espresso.core)
    implementation(libs.androidx.test.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
}
