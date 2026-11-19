// App.kt
package com.nvmex.networkhelper

import com.amap.api.maps.MapsInitializer
import android.util.Log
import com.nvmex.networkhelper.ui.onboarding.EnvironmentProbeStore
import com.nvmex.networkhelper.ui.settings.fonts.FontSizeConfig
import com.nvmex.networkhelper.util.diagnostics.DiagnosticLevel
import com.nvmex.networkhelper.util.diagnostics.DiagnosticReporter
import dev.ririxi.bunnylock.modem.BunnyDiagnostics
import be.mygod.vpnhotspot.CrashReporter
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class App : be.mygod.vpnhotspot.App() {

    override fun onCreate() {
        DiagnosticReporter.initialize(this)
        BunnyDiagnostics.sink = { channel, warning, message ->
            DiagnosticReporter.component(if (warning) DiagnosticLevel.WARNING else DiagnosticLevel.INFO,
                "bunnylock/$channel", message)
        }
        CrashReporter.diagnosticSink = { priority, tag, message, error ->
            val level = when {
                priority >= Log.ERROR -> DiagnosticLevel.ERROR
                priority >= Log.WARN -> DiagnosticLevel.WARNING
                else -> DiagnosticLevel.INFO
            }
            DiagnosticReporter.component(level, "vpnhotspot/$tag", message, error?.stackTraceToString().orEmpty())
        }
        super.onCreate()
        DiagnosticReporter.info("vpnhotspot", "VPN Hotspot ${be.mygod.vpnhotspot.BuildConfig.UPSTREAM_VERSION}")
        com.nvmex.networkhelper.util.modem.SystemQmiServices.initialize(this)

        // 高德地图 SDK 初始化
        runCatching {
            MapsInitializer.updatePrivacyShow(this, true, true)
            MapsInitializer.updatePrivacyAgree(this, true)
        }

        // 初始化字体配置（传入 Application Context）
        FontSizeConfig.init(applicationContext)

        // 监听 LSPosed 服务连接（onServiceBind / onServiceDied）
        EnvironmentProbeStore.registerFrameworkServiceListener(applicationContext)
    }
}


