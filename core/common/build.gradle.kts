plugins {
    alias(libs.plugins.kotlin.jvm)
}

// 纯 Kotlin/JVM：通用工具。coroutines 用 api 暴露，使依赖方拿到 Flow/Dispatcher 类型。
kotlin {
    jvmToolchain(17)
}

dependencies {
    api(libs.kotlinx.coroutines.core)
}
