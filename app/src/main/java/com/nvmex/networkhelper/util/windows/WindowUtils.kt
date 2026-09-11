package com.nvmex.networkhelper.util.windows

import android.content.Context
import android.os.Build
import android.util.DisplayMetrics
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.WindowInsets as CWindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

object WindowUtils {

    /**
     * 获取状态栏高度（Dp）
     * - 使用 Compose WindowInsets，真实反映当前是否有状态栏/刘海占位
     */
    @Composable
    fun getStatusBarHeight(context: Context): Dp {
        // 1) 先读实时的系统 inset（有状态栏/刘海时会 > 0）
        val insetTop = androidx.compose.foundation.layout.WindowInsets
            .statusBars
            .asPaddingValues()
            .calculateTopPadding()

        if (insetTop > 0.dp) {
            return insetTop
        }

        // 2) 沉浸式场景下 inset 可能返回 0，则回退到系统 dimen（稳定值）
        return with(LocalDensity.current) {
            val resId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
            val px = if (resId > 0) context.resources.getDimensionPixelSize(resId) else 0
            // 给一个兜底，极端机型拿不到资源时用常见的 24dp
            if (px > 0) px.toDp() else 24.dp
        }
    }


    /**
     * 获取导航栏高度（px，非 Composable）
     * - API >= 30：用 WindowMetrics + WindowInsets
     * - API < 30：仅在存在“软导航栏”时返回 dimen，否则返回 0（修复实体三键设备误报）
     */
    fun getNavigationBarHeight(context: Context): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getNavigationBarHeightNew(context)
        } else {
            // 只有确实存在“软导航栏”时才读取资源高度
            if (hasSoftKeys(context)) {
                val resId = context.resources.getIdentifier("navigation_bar_height", "dimen", "android")
                if (resId > 0) context.resources.getDimensionPixelSize(resId) else 0
            } else {
                0
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun getNavigationBarHeightNew(context: Context): Int {
        val wm = context.getSystemService(WindowManager::class.java)
        val insets = wm.currentWindowMetrics.windowInsets
        // 仅取底部导航栏 inset（无导航栏=0）
        return insets.getInsets(WindowInsets.Type.navigationBars()).bottom
    }

    /**
     * 检测是否存在软件导航栏（软键/手势区域）
     * - API >= 17：对比 realMetrics 与 metrics 是否有被系统栏“吃掉”的像素
     * - API < 17：回退到是否存在实体菜单/返回键的启发式判断
     */
    @Suppress("DEPRECATION")
    private fun hasSoftKeys(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= 17) {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val display = wm.defaultDisplay
            val real = DisplayMetrics()
            val usable = DisplayMetrics()
            display.getRealMetrics(real)
            display.getMetrics(usable)
            (real.widthPixels - usable.widthPixels) > 0 || (real.heightPixels - usable.heightPixels) > 0
        } else {
            // 老设备：若没有实体菜单键且没有实体返回键，基本可以认为有软导航栏
            !ViewConfiguration.get(context).hasPermanentMenuKey() &&
                    !KeyCharacterMap.deviceHasKey(KeyEvent.KEYCODE_BACK)
        }
    }

    /**
     * 获取导航栏高度（Dp，Composable）
     * - 直接使用 Compose 的 WindowInsets，可靠；无导航栏=0
     */
    @Composable
    fun getNavigationBarHeightDp(context: Context): Dp {
        return CWindowInsets.navigationBars
            .only(WindowInsetsSides.Bottom)
            .asPaddingValues()
            .calculateBottomPadding()
    }

    /**
     * 获取导航栏的一半高度（Dp，Composable）
     * - 兼容你现有大量调用
     */
    @Composable
    fun getNavigationBarHalfHeightDp(context: Context): Dp {
        return getNavigationBarHeightDp(context) * 0.5f
    }
}