# PyqCR ProGuard Rules

# Keep Retrofit interfaces
-keep,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}

# Keep Gson serialization/deserialization
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.pyqcr.data.model.** { *; }

# Keep Coil
-dontwarn coil.**

# OkHttp (already handled by default)
-dontwarn okhttp3.**
-dontwarn okio.**

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *