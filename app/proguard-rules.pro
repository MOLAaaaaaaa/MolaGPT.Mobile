# —— MolaGPT app R8 规则 ——
# 各 core 模块通过 consumer-rules.pro 提供了 kotlinx.serialization / Room / Ktor 的保留规则。

# kotlinx.serialization：保留所有 @Serializable 类型的 serializer
-keepclassmembers class * {
    @kotlinx.serialization.Serializable <methods>;
}
-keepclasseswithmembers,allowshrinking class **$$serializer { *; }

# 领域模型多态 sealed 序列化（fragments 存 JSON 需要）
-keep class com.molagpt.app.core.model.** { *; }

# Compose / Kotlin 元数据
-keep class kotlin.Metadata { *; }

# Tink（androidx.security:security-crypto 间接依赖）引用了一批仅编译期的 errorprone
# 注解，运行时不打包；R8 默认把这些缺类当致命错误。这里抑制告警即可（与 R8 生成的
# missing_rules.txt 一致，用通配以兼容 Tink 后续版本新增的注解引用）。
-dontwarn com.google.errorprone.annotations.**

# 关闭 release 的 debug 日志由 BuildConfig.DEBUG 门控（Logger sink）。
