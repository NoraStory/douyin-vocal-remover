# Project-specific ProGuard rules.
-keep class com.nora.douyinremover.** { *; }
-keep class com.nora.douyinremover.douyin.** { *; }
-keep class com.nora.douyinremover.audio.** { *; }
-keep class com.nora.douyinremover.settings.** { *; }
-keep class com.nora.douyinremover.updater.** { *; }
-keep class com.microsoft.onnxruntime.** { *; }
-keep class ai.onnxruntime.** { *; }
-keep class com.arthenica.ffmpegkit.** { *; }
-keepclassmembers class * {
    @kotlinx.serialization.Serializable <fields>;
}

# WorkManager：Room 生成的数据库实现类通过反射构造，
# R8 会误删 WorkDatabase_Impl.<init> 导致启动即闪退
# （androidx.work 自带 consumer rules 在 AGP 9.x 下未生效，需显式保留）
-keep class androidx.work.impl.WorkDatabase_Impl { *; }
-keep class androidx.work.impl.** { *; }
-keep class androidx.room.** { *; }
