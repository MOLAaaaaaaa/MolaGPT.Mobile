# Ktor / OkHttp / kotlinx.serialization 的消费方 R8 规则（被 :app 合入）。
# kotlinx.serialization 生成的 serializer 需保留。
-keepclasseswithmembers,allowshrinking class * {
    @kotlinx.serialization.Serializable <methods>;
}
-keepclassmembers class **$$serializer { *; }
-keep,includedescriptorclasses class com.molagpt.app.core.network.**$$serializer { *; }

# Ktor 反射式引擎加载
-keep class io.ktor.client.engine.okhttp.** { *; }
-dontwarn org.slf4j.**
-dontwarn io.ktor.**
