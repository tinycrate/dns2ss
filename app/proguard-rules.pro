# Keep the hev JNI bridge and its native entry points.
-keep class hev.htproxy.TProxyService { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}
