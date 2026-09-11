package com.nvmex.networkhelper.xposed

import android.app.Application
import android.content.Context
import android.os.Looper
import android.util.Log
import com.nvmex.networkhelper.xposed.woker.AmbrRespTraceHooks
import com.nvmex.networkhelper.xposed.woker.EngineerBandLockObserverEntry
import com.nvmex.networkhelper.xposed.woker.QosTrackerEntry
import de.robv.android.xposed.AndroidAppHelper
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

class XposedInit : IXposedHookLoadPackage {

    companion object {
        private const val LOG_TAG = "NetworkHelper999"

        private const val ENGINEER_PKG = "com.oplus.engineernetwork"
        private const val GYWC_PKG = "cn.gywc.gycesu"
        private const val SUBSYS_PKG = "com.oplus.subsys"

        private const val PHONE_PKG = "com.android.phone"
        private const val ANDROID_PKG = "android"
        private const val CELLULAR_PRO_PKG = "make.more.r2d2.google.cellular_pro"
        private val hooked = HashSet<String>()

        // 只做存在性验证（路标），不在这里直接 hook。
        private const val NRCA_PROXY_CLASS = "com.oplus.subsys.radio.RadioProxyJ"

        private const val ANDROID_WIFI_STACK_PKG = "com.android.wifi"
        private const val WIFI_HAL_PROC = "android.hardware.wifi-service"
        private const val OPLUS_WIFI_AIDL_PROC = "vendor.oplus.hardware.wifi-aidl-service"

        // 仅用于过滤：`service list` 中 opluswifikitservice 对应的接口 token。
        private const val IFACE_OPLUS_WIFI_MGR = "android.net.wifi.IOplusWifiManager"
        private const val IFACE_WIFI_MGR = "android.net.wifi.IWifiManager"
        private const val IFACE_WIFICOND = "android.net.wifi.nl80211.IWificond"
        private const val IFACE_WIFI_HAL = "android.hardware.wifi.IWifi"
        private const val IFACE_OPLUS_WIFI_HAL = "vendor.oplus.hardware.wifi.IOplusWifiService"
    }

    // system_server / com.oplus.subsys 共用入口委托。
    private val delegates: List<IXposedHookLoadPackage> by lazy {
        listOf(
            HookEntry(), // NRCA / Telephony / Radio
            EngineerHookEntry(), // Engineer 插件
            PluginMonitor(),
            PluginTextModifier(), //添加注释
            QosTrackerEntry(), // QOS数据用于主页
            EngineerBandLockObserverEntry(), //锁频段 探索中
        )
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        // 防止同一进程重复执行。
        val key = "${lpparam.packageName}:${lpparam.processName}"
        if (!hooked.add(key)) return

        if (EnvironmentProbeReporter.isProbeTarget(lpparam.packageName)) {
            EnvironmentProbeReporter.reportIfPossible(lpparam.packageName, lpparam.processName)
            EnvironmentProbeReporter.hookApplicationAttach(lpparam.packageName, lpparam.processName)
        }

        when {
            // 1) system_server（android / android）。
            lpparam.packageName == ANDROID_PKG && lpparam.processName == ANDROID_PKG -> {
                logCN(lpparam, "入口", "已注入 system_server（系统进程）")
                preludeSystemServer(lpparam)
                // system_server 是 WiFi binder 的关键发起方之一。
                preludeWifi(lpparam)
                printClassExistence(lpparam, lpparam.classLoader, NRCA_PROXY_CLASS)

                dispatchDelegates(lpparam, sceneName = "system_server")
            }

            // 2) com.oplus.subsys（NRCA Java 代理层所在进程）。
            lpparam.packageName == SUBSYS_PKG && lpparam.processName.startsWith(SUBSYS_PKG) -> {
                logCN(lpparam, "入口", "已注入 subsys 相关进程：${lpparam.processName}")
                preludeSubsys(lpparam)

                val app = AndroidAppHelper.currentApplication()
                if (app != null) {
                    // 给 AMBR / QoS trace 设置 context。
                    AmbrRespTraceHooks.setAppContext(app)
                } else {
                    logCN(
                        lpparam,
                        "WARN",
                        "currentApplication() = null，file fallback 可能写 externalFilesDir 失败"
                    )
                }

                printClassExistence(lpparam, lpparam.classLoader, NRCA_PROXY_CLASS)
                dispatchDelegates(lpparam, sceneName = "subsys:${lpparam.processName}")
            }

            lpparam.packageName == PHONE_PKG && lpparam.processName.startsWith(PHONE_PKG) -> {
                logCN(lpparam, "入口", "已注入电话相关进程：${lpparam.processName}")
                preludeSubsys(lpparam)
                printClassExistence(lpparam, lpparam.classLoader, NRCA_PROXY_CLASS)
                dispatchDelegates(lpparam, sceneName = "subsys:${lpparam.processName}")
            }

            // 2.8) Cellular Pro（三方 App，用于观察其 Binder 调用）。
            lpparam.packageName == CELLULAR_PRO_PKG && lpparam.processName.startsWith(
                CELLULAR_PRO_PKG
            ) -> {
                logCN(lpparam, "入口", "已注入 cellularpro 进程：${lpparam.processName}")
                dispatchDelegates(lpparam, sceneName = "cellularpro:${lpparam.processName}")
            }

            // 3) 工程模式 Host（仅保留 ENC）。
            lpparam.packageName == ENGINEER_PKG && lpparam.processName.startsWith(ENGINEER_PKG) -> {
                logCN(lpparam, "入口", "已注入工程模式进程：${lpparam.processName}")

                // 仅主进程需要 ENC。
                if (lpparam.processName == ENGINEER_PKG) {
                    logCN(lpparam, "入口", "主进程：执行解密开关")
                    hookEngineerHostENC(lpparam)
                }

                // 让 EngineerHookEntry 能在 :plugin 进程运行。
                dispatchDelegates(lpparam, sceneName = "engineer:${lpparam.processName}")
            }

            // 4) WiFi 相关进程。
            lpparam.packageName == ANDROID_WIFI_STACK_PKG || lpparam.processName == ANDROID_WIFI_STACK_PKG -> {
                logCN(lpparam, "入口", "已注入 WiFiStack 进程：${lpparam.processName}")
                preludeWifi(lpparam)
                dispatchDelegates(lpparam, sceneName = "wifi:${lpparam.processName}")
            }

            lpparam.processName == WIFI_HAL_PROC -> {
                logCN(lpparam, "入口", "已注入 WiFi HAL 进程：${lpparam.processName}")
                preludeWifi(lpparam)
                dispatchDelegates(lpparam, sceneName = "wifi:${lpparam.processName}")
            }

            lpparam.processName == OPLUS_WIFI_AIDL_PROC -> {
                logCN(lpparam, "入口", "已注入 Oplus WiFi AIDL 进程：${lpparam.processName}")
                preludeWifi(lpparam)
                dispatchDelegates(lpparam, sceneName = "wifi:${lpparam.processName}")
            }

            lpparam.packageName == GYWC_PKG && lpparam.processName.startsWith(GYWC_PKG) -> {
                logCN(lpparam, "入口", "已注入 GYWC 进程：${lpparam.processName}")
                dispatchDelegates(lpparam, sceneName = "gywc:${lpparam.processName}")
            }

            else -> return
        }
    }

    // system_server 前置（仅日志/验证）。
    private fun preludeSystemServer(lpparam: XC_LoadPackage.LoadPackageParam) {
        logCN(lpparam, "系统", "准备安装 system_server 侧监听逻辑")
        logCN(lpparam, "系统", "主线程 Looper=${Looper.getMainLooper()}")
    }

    // com.oplus.subsys 前置（仅日志/验证）。
    private fun preludeSubsys(lpparam: XC_LoadPackage.LoadPackageParam) {
        logCN(lpparam, "子系统", "准备安装 subsys 侧监听逻辑")
        logCN(lpparam, "子系统", "主线程 Looper=${Looper.getMainLooper()}")
    }

    // WiFi 前置（仅日志/验证）。
    private fun preludeWifi(lpparam: XC_LoadPackage.LoadPackageParam) {
        // 1) 必出日志：证明注入日志通道正常。
        logCN(lpparam, "WiFi", "preludeWifi() alive, cl=${lpparam.classLoader}")

        // 2) 抓取 WiFi/Oplus 相关 binder 调用（核心）。
        hookBinderProxyTransactWifiFiltered(lpparam)

        // 3) dump ServiceManager.getService/addService（辅助定位服务调用关系）。
        hookServiceManagerWifiNames(lpparam)
    }

    private fun hookBinderProxyTransactWifiFiltered(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val bp = XposedHelpers.findClass("android.os.BinderProxy", lpparam.classLoader)

            XposedBridge.hookAllMethodsNative(bp, "transact") { chain ->
                val args = chain.args
                val code = (args.getOrNull(0) as? Int)
                val data = (args.getOrNull(1) as? android.os.Parcel)
                val flags = (args.getOrNull(3) as? Int) ?: 0

                if (code != null && data != null) {
                    // 复制一份 Parcel 来解析 interface token，避免破坏原始调用。
                    val raw = runCatching { data.marshall() }.getOrNull()
                    if (raw != null) {
                        val p = android.os.Parcel.obtain()
                        try {
                            p.unmarshall(raw, 0, raw.size)
                            p.setDataPosition(0)
                            val iface = runCatching { p.readString() }.getOrNull()

                            // 仅关注目标接口（来自 service list 的真实 iface）。
                            val hit = iface == IFACE_OPLUS_WIFI_MGR ||
                                    iface == IFACE_WIFI_MGR ||
                                    iface == IFACE_WIFICOND ||
                                    iface == IFACE_WIFI_HAL ||
                                    iface == IFACE_OPLUS_WIFI_HAL

                            if (hit) {
                                logCN(
                                    lpparam,
                                    "Binder",
                                    "transact iface=$iface code=$code flags=$flags proc=${lpparam.processName}"
                                )
                            }
                        } finally {
                            p.recycle()
                        }
                    }
                }

                chain.proceed()
            }

            logCN(lpparam, "WiFi", "[OK] hooked BinderProxy.transact (wifi filtered)")
        } catch (t: Throwable) {
            logCN(
                lpparam,
                "WiFi",
                "[FAIL] hookBinderProxy.transact failed: ${t.javaClass.simpleName}:${t.message}"
            )
            XposedBridge.log(t)
        }
    }

    private fun hookServiceManagerWifiNames(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val sm = XposedHelpers.findClass("android.os.ServiceManager", lpparam.classLoader)

            XposedBridge.hookAllMethodsNative(sm, "getService") { chain ->
                val name = chain.args.getOrNull(0) as? String
                if (name != null) {
                    val hit = name.contains("wifi", true) ||
                            name.contains("wlan", true) ||
                            name.contains("oplus", true)
                    if (hit) logCN(
                        lpparam,
                        "SM",
                        "getService(name=$name) proc=${lpparam.processName}"
                    )
                }
                chain.proceed()
            }

            XposedBridge.hookAllMethodsNative(sm, "addService") { chain ->
                val name = chain.args.getOrNull(0) as? String
                if (name != null) {
                    val hit = name.contains("wifi", true) ||
                            name.contains("wlan", true) ||
                            name.contains("oplus", true)
                    if (hit) logCN(
                        lpparam,
                        "SM",
                        "addService(name=$name) proc=${lpparam.processName}"
                    )
                }
                chain.proceed()
            }

            logCN(lpparam, "WiFi", "[OK] hooked ServiceManager get/addService (wifi filtered)")
        } catch (t: Throwable) {
            logCN(
                lpparam,
                "WiFi",
                "[FAIL] hookServiceManager failed: ${t.javaClass.simpleName}:${t.message}"
            )
            XposedBridge.log(t)
        }
    }

    // delegates 分发。
    private fun dispatchDelegates(
        lpparam: XC_LoadPackage.LoadPackageParam,
        sceneName: String
    ) {
        delegates.forEach { delegate ->
            val name = delegate.javaClass.simpleName
            runCatching {
                logCN(lpparam, "分发", "进入 $name（场景=$sceneName）")
                delegate.handleLoadPackage(lpparam)
                logCN(lpparam, "分发", "退出 $name（场景=$sceneName）")
            }.onFailure { e ->
                Log.e(
                    LOG_TAG,
                    "[分发][包名=${lpparam.packageName}][进程=${lpparam.processName}] $name 执行失败：${e.javaClass.simpleName}: ${e.message}",
                    e
                )
            }
        }
    }

    // 工程模式 ENC：保留 3 个逻辑。
    private fun hookEngineerHostENC(lpparam: XC_LoadPackage.LoadPackageParam) {
        // 1) Application.attach（调试验证）。
        XposedBridge.hookAllMethodsNative(Application::class.java, "attach") { chain ->
            val result = chain.proceed()
            if (chain.args.getOrNull(0) is Context) {
                logCN(lpparam, "解密", "已 Hook Application.attach（用于验证进程已进入）")
            }
            result
        }

        // 2) HostApplication.Companion#getMIsEncrypt -> false
        val companionCls = XposedHelpers.findClass(
            "com.oplus.engineernetwork.HostApplication\$Companion",
            lpparam.classLoader,
        )
        XposedBridge.hookAllMethodsNative(companionCls, "getMIsEncrypt") { _ -> false }
        logCN(lpparam, "解密", "已安装 getMIsEncrypt -> false")

        // 3) HostApplication#onCreate -> setMIsEncrypt(false)
        val hostCls = XposedHelpers.findClass(
            "com.oplus.engineernetwork.HostApplication",
            lpparam.classLoader,
        )
        XposedBridge.hookAllMethodsNative(hostCls, "onCreate") { chain ->
            val result = chain.proceed()
            runCatching {
                val companion = XposedHelpers.getStaticObjectField(hostCls, "Companion")
                XposedHelpers.callMethod(companion, "setMIsEncrypt", false)
                logCN(lpparam, "解密", "已强制 setMIsEncrypt(false)")
            }.onFailure {
                logCN(lpparam, "解密", "setMIsEncrypt(false) 执行失败：${it.message}")
            }
            result
        }
        logCN(lpparam, "解密", "已安装 onCreate -> setMIsEncrypt(false)")
    }

    // 类存在性验证（只验证，不直接 hook）。
    private fun printClassExistence(
        lpparam: XC_LoadPackage.LoadPackageParam,
        cl: ClassLoader,
        className: String
    ) {
        val exists = runCatching {
            XposedHelpers.findClass(className, cl)
            true
        }.getOrElse { false }

        if (exists) {
            logCN(lpparam, "验证", "类存在：$className（可在本进程直接 Hook）")
        } else {
            logCN(lpparam, "验证", "类不存在：$className（本进程 ClassPath 中未找到）")
        }
    }

    // 统一日志（中文）。
    private fun logCN(lpparam: XC_LoadPackage.LoadPackageParam, module: String, msg: String) {
//        val line = "[$module][包名=${lpparam.packageName}][进程=${lpparam.processName}][线程=${Thread.currentThread().name}] $msg"
//        Log.i(LOG_TAG, line)
//        XposedBridge.log("$LOG_TAG $line")
    }
}
