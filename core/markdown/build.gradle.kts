plugins {
    alias(libs.plugins.kotlin.jvm)
}

// 纯 Kotlin/JVM：commonmark 是纯 Java，解析逻辑无需 Android。
// 产出中性的块/行内模型，由 :core:render 映射成 Compose（保持 markdown 层不依赖 UI）。
kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":core:model"))
    implementation(libs.commonmark)
    implementation(libs.commonmark.ext.gfm.tables)
    implementation(libs.commonmark.ext.gfm.strikethrough)
    // mola-ui 围栏里的 JSON 只按树读，不需要序列化插件。
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
}
