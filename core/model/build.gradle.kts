plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

// 纯 Kotlin/JVM 模块：无任何 Android 依赖，保证领域模型纯净、可单测、
// 且对 Compose 强跳过(strong skipping)友好（外部模块的稳定类自动可跳过）。
kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
}
