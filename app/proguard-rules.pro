# Keep models and Gson serialization
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-keep class com.flowtv.app.model.** { *; }
-keep class androidx.media3.** { *; }
