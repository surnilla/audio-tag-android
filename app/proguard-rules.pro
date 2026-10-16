# Add project-specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in the Android SDK tools/proguard/proguard-android-optimize.txt

# Preserve line number information for debugging stack traces.
-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to hide the original
# source file name.
#-renamesourcefileattribute SourceFile

# Keep BLE callback classes intact
-keep class com.audiotag.app.ble.** { *; }
