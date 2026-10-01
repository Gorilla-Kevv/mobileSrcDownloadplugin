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

# 悬浮窗 Compose 宿主反射绑定（FloatingWindowService.bindOwners 的 Class.forName 目标）
-keep class androidx.lifecycle.ViewTreeLifecycleOwner { *; }
-keep class androidx.savedstate.ViewTreeSavedStateRegistryOwner { *; }

# 说明：SP 字段名与序列化键是运行时字符串（model 类已整体 keep）；
# DataStore/OkHttp/Coil/Compose 均自带 consumer rules，无需重复。
# 诊断日志（Log.d/w）在 release 保留——个人项目诊断优先，不做 assumenosideeffects 剥离。
