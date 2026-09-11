package com.nvmex.networkhelper.viewmodel.signal

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.nvmex.networkhelper.model.network.NrCaCarrier
import com.nvmex.networkhelper.model.network.NrCaInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject

/**
 * 仅负责接收实时NRCA广播，不进行任何缓存
 * 每次都是最新数据，直接透传
 */
class BroadcastNrCaTracker(
    private val app: Application
) {

    companion object {
        const val ACTION_NRCA_UPDATE = "com.nvmex.networkhelper.action.NRCA_UPDATE"
        const val EXTRA_SLOT = "slot"
        const val EXTRA_TYPE = "type"
        const val EXTRA_JSON = "json"

        // 广播是显式setPackage，所以用NOT_EXPORTED
        private const val RECEIVER_FLAGS = ContextCompat.RECEIVER_NOT_EXPORTED
    }

    // slot -> NrCaInfo (仅内存中的最新数据)
    private val _bySlot = MutableStateFlow<Map<Int, NrCaInfo>>(emptyMap())
    val bySlot: StateFlow<Map<Int, NrCaInfo>> = _bySlot

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_NRCA_UPDATE) return

            val slot = intent.getIntExtra(EXTRA_SLOT, -1)
            if (slot < 0) return

            val type = intent.getIntExtra(EXTRA_TYPE, -1)
            val json = intent.getStringExtra(EXTRA_JSON) ?: return

            // 实时解析并直接更新内存（无缓存）
            runCatching {
                parseNrCaJson(slot, type, json).also { info ->
                    _bySlot.update { old -> old + (slot to info) }
                }
            }.onFailure {
                // 解析失败时可选：清除该slot的数据
                _bySlot.update { old -> old - slot }
            }
        }
    }

    init {
        // 不再从磁盘恢复数据，只注册接收器
        val filter = IntentFilter(ACTION_NRCA_UPDATE)
        ContextCompat.registerReceiver(app, receiver, filter, RECEIVER_FLAGS)
    }

    fun stop() {
        runCatching { app.unregisterReceiver(receiver) }
    }

    /**
     * 手动清除某个卡槽的数据（可选）
     */
    fun clearSlot(slot: Int) {
        _bySlot.update { old -> old - slot }
    }

    /**
     * 清除所有数据（可选）
     */
    fun clearAll() {
        _bySlot.value = emptyMap()
    }
}

private fun parseNrCaJson(slot: Int, type: Int, json: String): NrCaInfo {
    val root = JSONObject(json)
    val list = root.optJSONArray("list")

    val carriers = buildList {
        if (list != null) {
            for (i in 0 until list.length()) {
                val o = list.optJSONObject(i) ?: continue
                add(
                    NrCaCarrier(
                        ccId = o.optInt("ccId", -1),
                        sccId = o.optInt("sccId", -1),
                        pci = o.optInt("pci", -1),
                        bandRaw = o.optInt("band", -1),
                        bandText = "-",
                        dlArfcn = o.optInt("dlEarfcn", -1),
                        dlStateRaw = o.optInt("dlState", -1),
                        dlStateText = "-",
                        dlBwRaw = o.optInt("dlBw", -1),
                        dlBwText = "-",
                        ulStateRaw = o.optInt("ulState", -1),
                        ulStateText = "-",
                        ulBwRaw = o.optInt("ulBw", -1),
                        ulBwText = "-"
                    )
                )
            }
        }
    }

    return NrCaInfo(
        slot = slot,
        type = type,
        carriers = carriers,
        updatedAt = System.currentTimeMillis() // 使用当前时间戳
    )
}