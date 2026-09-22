# Project-specific ProGuard rules.
-keep class com.nora.douyinremover.** { *; }
-keep class com.nora.douyinremover.douyin.** { *; }
-keep class com.nora.douyinremover.audio.** { *; }
-keep class com.nora.douyinremover.settings.** { *; }
-keep class com.microsoft.onnxruntime.** { *; }
-keep class ai.onnxruntime.** { *; }
-keep class com.arthenica.ffmpegkit.** { *; }
-keepclassmembers class * {
    @kotlinx.serialization.Serializable <fields>;
}
