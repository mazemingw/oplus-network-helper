# The root helper is launched by name via app_process, so R8 cannot see its entry point.
# The backend packages hold the helper's half of each backend, next to the app's half.
-keep class dev.ririxi.bunnylock.root.** { *; }
-keep class dev.ririxi.bunnylock.shizuku.ShizukuService { *; }
-keep class dev.ririxi.bunnylock.backend.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}
