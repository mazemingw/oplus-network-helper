package com.nvmex.networkhelper.util.telephony

import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import android.util.Log
import com.nvmex.networkhelper.R
import kotlinx.coroutines.*

/** Toggles only the USER NR bit on the default data subscription, using the Android phone service. */
class FiveGTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var refreshJob: Job? = null
    private var clickJob: Job? = null

    override fun onStartListening() { super.onStartListening(); refresh(toggle = false) }
    override fun onClick() {
        super.onClick()
        if (isLocked) unlockAndRun { refresh(toggle = true) } else refresh(toggle = true)
    }
    private fun refresh(toggle: Boolean) {
        if (Build.VERSION.SDK_INT < 31) {
            qsTile?.apply { state = Tile.STATE_UNAVAILABLE; subtitle = "需要 Android 12 及以上"; updateTile() }
            return
        }
        if (toggle && clickJob?.isActive == true || !toggle && (refreshJob?.isActive == true || clickJob?.isActive == true)) return
        val previousRead = refreshJob
        val job = scope.launch {
            if (toggle) previousRead?.join()
            try {
                qsTile?.apply { subtitle = if (toggle) "正在设置…" else "正在读取…"; updateTile() }
                val enabled = if (toggle) FiveGNetwork.toggle(this@FiveGTileService) else FiveGNetwork.read(this@FiveGTileService)
                qsTile?.apply {
                    label = "5G 网络"
                    state = if (enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
                    subtitle = if (enabled) "允许 5G" else "不允许 5G"
                    contentDescription = "$label，$subtitle"; updateTile()
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                Log.w("FiveGTile", "系统 5G 设置访问失败", e)
                // Keep transient failures tappable so a root grant or a SIM change can be retried.
                qsTile?.apply { state = Tile.STATE_INACTIVE; subtitle = "点按重试 · 长按设置"; updateTile() }
                if (toggle) Toast.makeText(this@FiveGTileService, e.message ?: "5G 设置失败", Toast.LENGTH_LONG).show()
            }
        }
        if (toggle) clickJob = job else refreshJob = job
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    companion object {
        fun requestAdd(context: Context) {
            if (Build.VERSION.SDK_INT >= 33) {
                val manager = context.getSystemService(StatusBarManager::class.java)
                manager.requestAddTileService(ComponentName(context, FiveGTileService::class.java), "5G 网络",
                    Icon.createWithResource(context, R.drawable.ic_tile_5g), context.mainExecutor) { result ->
                    if (result != StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED && result != StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED)
                        Toast.makeText(context, "可在控制中心编辑磁贴中添加“5G 网络”", Toast.LENGTH_LONG).show()
                }
            } else Toast.makeText(context, "请在控制中心的编辑磁贴页面添加“5G 网络”；开关需要 Android 12 及以上和 Root", Toast.LENGTH_LONG).show()
        }
    }
}
