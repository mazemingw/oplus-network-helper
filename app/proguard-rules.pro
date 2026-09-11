############################################
# 基础属性
############################################
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod

############################################
# Xposed / LSPosed / HiddenApi
############################################
-keep class de.robv.android.xposed.** { *; }
-keep class org.lsposed.hiddenapibypass.** { *; }

# 你的 Xposed 入口和 Hook 逻辑
-keep class com.nvmex.networkhelper.xposed.** { *; }

############################################
# Hilt / Dagger
############################################
# Application / Activity / Hilt 生成代码依赖注解和继承链
-keep class * extends android.app.Application
-keep class * extends androidx.activity.ComponentActivity
-keep class * extends androidx.lifecycle.ViewModel

# Hilt 相关注解类的字节码信息需要保留
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-dontwarn dagger.hilt.internal.**
-dontwarn javax.inject.**
############################################
# 上传工参 / 随行随录：请求与响应 DTO
############################################
-keep class com.nvmex.networkhelper.network.model.BatchUpsertResp { *; }

-keep class com.nvmex.networkhelper.network.model.LteBatchUpsertReq { *; }
-keep class com.nvmex.networkhelper.network.model.LteCellParamUploadItem { *; }

-keep class com.nvmex.networkhelper.network.model.NrBatchUpsertReq { *; }
-keep class com.nvmex.networkhelper.network.model.NrCellParamUploadItem { *; }

############################################
# Retrofit service 接口：这组先保守一点
############################################
-keep interface com.nvmex.networkhelper.network.api.ApiService { *; }

############################################
# safeApiCall / ApiResult 泛型链：先别让 R8 瞎折腾
############################################
-keep class com.nvmex.networkhelper.network.base.ApiResult { *; }
-keep class com.nvmex.networkhelper.network.base.ApiResult$* { *; }
-keep class com.nvmex.networkhelper.network.base.SafeApiCallKt { *; }

############################################
# 这一组是你当前网络查询/上传最容易受影响的模型，先保住
############################################
-keep class com.nvmex.networkhelper.network.model.** { *; }
-keep class com.nvmex.networkhelper.network.map.** { *; }
-keep class com.nvmex.networkhelper.model.menu.** { *; }
############################################
# Room
############################################
# Room Database / Dao / Entity
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Dao class * { *; }
-keep @androidx.room.Entity class * { *; }

# Room 生成实现类
-keep class * extends androidx.room.RoomDatabase_Impl
-keep class *_Impl { *; }
-keep class *_Impl$* { *; }

############################################
# Retrofit
############################################
# 保留 Retrofit 接口方法注解
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}

# Kotlin suspend / 泛型签名常要用到
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations, AnnotationDefault

############################################
# Gson
############################################
# 仅保留被 @SerializedName 标记的字段
-keepclassmembers,allowshrinking,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# 你的网络 DTO 可以保守一点，只保成员，不保整包所有行为
-keepclassmembers class com.nvmex.networkhelper.network.model.** { <fields>; }
-keepclassmembers class com.nvmex.networkhelper.network.map.** { <fields>; }
-keepclassmembers class com.nvmex.networkhelper.model.menu.** { <fields>; }
-keepclassmembers class com.nvmex.networkhelper.model.home.** { <fields>; }

############################################
# Native / JNI
############################################
-keepclasseswithmembernames class * {
    native <methods>;
}

-keep class com.nvmex.networkhelper.iperf.IperfNative$StreamCallback {
    public void onLine(java.lang.String);
}

############################################
# WebView JS Bridge（仅当存在时保留）
############################################
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

############################################
# 高德地图
############################################
-dontwarn com.amap.**
-dontwarn com.autonavi.**
-dontwarn com.loc.**

# 你当前明确用了 3D 地图
-keep class com.amap.api.maps.** { *; }
-keep class com.amap.api.location.** { *; }
-keep class com.autonavi.** { *; }
-keep class com.loc.** { *; }
-keep class com.autonavi.aps.amapapi.model.** { *; }
-keep class com.nvmex.networkhelper.ui.map.** { *; }
-keep class com.nvmex.networkhelper.util.map.** { *; }

# 如果你后续实际启用了定位/搜索，再打开下面这些
# -keep class com.amap.api.location.** { *; }
# -keep class com.amap.api.fence.** { *; }
# -keep class com.loc.** { *; }
# -keep class com.autonavi.aps.amapapi.model.** { *; }
# -keep class com.amap.api.services.** { *; }

############################################
# 你项目里明确依赖反射/广播解析的少量类
############################################
-keep class com.nvmex.networkhelper.model.network.QosData { *; }
-keep class com.nvmex.networkhelper.model.network.GlobalQosEvent { *; }

############################################
# 枚举
############################################
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

############################################
# 第三方库 dontwarn（保留你原来真正需要的）
############################################
-dontwarn be.mygod.vpnhotspot.BR
-dontwarn com.sun.jna.**
-dontwarn java.lang.management.**
-dontwarn javax.naming.**
-dontwarn lombok.Generated
-dontwarn sun.net.spi.nameservice.**

-dontwarn com.github.luben.zstd.**
-dontwarn org.brotli.dec.BrotliInputStream
-dontwarn org.tukaani.xz.**
-dontwarn org.osgi.**
-dontwarn org.apache.tools.ant.**
-dontwarn javax.cache.**
-dontwarn javax.management.**
-dontwarn javax.xml.bind.**
-dontwarn javax.xml.crypto.**
-dontwarn javax.xml.stream.**
-dontwarn net.sf.saxon.**
-dontwarn org.openxmlformats.schemas.**
-dontwarn org.apache.xmlbeans.**
-dontwarn com.microsoft.schemas.**
-dontwarn com.sun.javadoc.**
-dontwarn java.awt.**
-dontwarn java.beans.**
-dontwarn edu.umd.cs.findbugs.annotations.SuppressFBWarnings
-dontwarn sun.misc.Contended
