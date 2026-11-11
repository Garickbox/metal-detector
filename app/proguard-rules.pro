# Keep Reading data class (Compose + StateFlow)
-keep class com.garickbox.metaldetector.Reading { *; }
# Keep StateFlow fields in Companion
-keepclassmembers class com.garickbox.metaldetector.MetalDetectorService$Companion { *; }
# Coroutines flow internals are fine
-dontwarn kotlinx.coroutines.**
