# 高德地图/定位 SDK：反射与 JNI 较多，AGP 9 release 的 optimization（R8）裁剪激进，需加 keep 保险
-keep class com.amap.api.** { *; }
-keep class com.autonavi.** { *; }
-keep class com.loc.** { *; }
-dontwarn com.amap.**
-dontwarn com.autonavi.**
-dontwarn com.loc.**
