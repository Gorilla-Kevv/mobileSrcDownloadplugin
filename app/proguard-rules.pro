# 保留 OkHttp / 序列化运行时所需符号
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn okhttp3.**
-dontwarn okio.**
-dontnote retrofit2.**

# 保留解析内核的数据模型（反射与序列化可能用到）
-keep class com.clipdown.parser.model.** { *; }
-keepclassmembers class * {
    @kotlinx.serialization.Serializable <fields>;
}
