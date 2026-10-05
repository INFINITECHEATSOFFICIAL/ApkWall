# apksig uses reflective/provider-based crypto APIs; retain its public entry points.
-keep class com.android.apksig.** { *; }
-dontwarn com.android.apksig.**

# ARSCLib performs binary manifest/resource and archive operations, including reflective internals.
-keep class com.reandroid.** { *; }
-dontwarn com.reandroid.**
