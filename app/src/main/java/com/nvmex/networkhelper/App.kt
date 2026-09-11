// App.kt
package com.nvmex.networkhelper

import com.amap.api.maps.MapsInitializer
import com.nvmex.networkhelper.ui.onboarding.EnvironmentProbeStore
import com.nvmex.networkhelper.ui.settings.fonts.FontSizeConfig
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class App : be.mygod.vpnhotspot.App() {

    override fun onCreate() {
        super.onCreate()

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


