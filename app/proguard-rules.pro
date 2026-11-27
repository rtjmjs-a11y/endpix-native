# Compose/AndroidX 自带 consumer rules; 原生 JNI 方法保留
-keep class com.endpix.nativeapp.MainActivity { native <methods>; }
-keepclasseswithmembernames class * { native <methods>; }
