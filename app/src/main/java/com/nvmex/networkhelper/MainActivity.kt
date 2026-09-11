package com.nvmex.networkhelper


import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.nvmex.networkhelper.ui.base.AppRoot
import com.nvmex.networkhelper.ui.onboarding.EnvironmentGuideActivity
import com.nvmex.networkhelper.ui.onboarding.EnvironmentGuidePrefs
import com.nvmex.networkhelper.ui.settings.AppLanguageManager
import com.nvmex.networkhelper.ui.theme.NetworkHelperTheme
import com.nvmex.networkhelper.xposed.DiagRouterClient
import dagger.hilt.android.AndroidEntryPoint
import android.content.Intent

@AndroidEntryPoint
class MainActivity : ComponentActivity() {


    private val targetPkg = "com.oplus.engineernetwork"
    private val targetActivity = "com.oplus.engineernetwork/.MainActivity"

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguageManager.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!EnvironmentGuidePrefs.isCompleted(this)) {
            startActivity(Intent(this, EnvironmentGuideActivity::class.java))
            finish()
            return
        }

        // 标准 edge-to-edge，避免 FLAG_LAYOUT_NO_LIMITS 在新系统上的异常 inset 行为
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.Transparent.toArgb()
        window.navigationBarColor = Color.Transparent.toArgb()


        DiagRouterClient()

        setContent {
            NetworkHelperTheme {
                AppRoot(
                    targetPkg = targetPkg,
                    targetActivity = targetActivity
                )
            }
        }

    }
}












