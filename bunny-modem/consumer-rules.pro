# The root helper is launched by name via app_process, so R8 cannot see its entry point.
-keep class dev.ririxi.bunnylock.root.** { *; }
-keep class dev.ririxi.bunnylock.shizuku.ShizukuService { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}
