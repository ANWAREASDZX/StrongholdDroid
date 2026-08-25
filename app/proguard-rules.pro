# ProGuard rules for StrongholdDroid
# Keep all classes annotated with @Keep (AGP's default behavior)
-keep @androidx.annotation.Keep class * { *; }

# Keep native JNI entry points
-keepclasseswithmembernames class * {
    native <methods>;
}

# StrongholdDroidApp — Application class
-keep class com.strongholddroid.emulator.StrongholdDroidApp { *; }

# EmulatorService — referenced from manifest
-keep class com.strongholddroid.emulator.emulator.EmulatorService { *; }

# Activities referenced from the manifest
-keep class com.strongholddroid.emulator.ui.MainActivity { *; }
-keep class com.strongholddroid.emulator.ui.GameManagerActivity { *; }
-keep class com.strongholddroid.emulator.ui.SettingsActivity { *; }
-keep class com.strongholddroid.emulator.ui.ControlConfigActivity { *; }

# FileProvider
-keep class androidx.core.content.FileProvider { *; }

# kotlinx.serialization — keeps the generated serializers
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasses class **$$serializer { *; }

# GamepadMapper reflective access to MotionEvent axis constants
-keepclassmembers class android.view.MotionEvent {
    public static float getAxisValue(int, int);
}
