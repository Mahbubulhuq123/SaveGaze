# SaveGaze Release ProGuard / R8 Rules

# Keep ONNX Runtime Java/JNI classes and native callbacks intact
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# Preserve source file and line numbers for readable stack traces
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
