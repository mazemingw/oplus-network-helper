package com.nvmex.networkhelper.viewmodel.signal

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.nvmex.networkhelper.model.network.LteCaCarrier
import com.nvmex.networkhelper.model.network.LteCaInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject

class BroadcastLteCaTracker(
    private val app: Application
) {

    companion object {
        const val ACTION_LTECA_UPDATE = "com.nvmex.networkhelper.action.LTECA_UPDATE"
        const val EXTRA_SLOT = "slot"
        const val EXTRA_JSON = "json"

        private const val RECEIVER_FLAGS = ContextCompat.RECEIVER_NOT_EXPORTED
    }

    private val _bySlot = MutableStateFlow<Map<Int, LteCaInfo>>(emptyMap())
    val bySlot: StateFlow<Map<Int, LteCaInfo>> = _bySlot

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_LTECA_UPDATE) return

            val slot = intent.getIntExtra(EXTRA_SLOT, -1)
            if (slot < 0) return

            val json = intent.getStringExtra(EXTRA_JSON) ?: return

            runCatching {
                parseLteCaJson(slot, json).also { info ->
                    _bySlot.update { old -> old + (slot to info) }
                }
            }.onFailure {
                _bySlot.update { old -> old - slot }
            }
        }
    }

    init {
        val filter = IntentFilter(ACTION_LTECA_UPDATE)
        ContextCompat.registerReceiver(app, receiver, filter, RECEIVER_FLAGS)
    }

    fun stop() {
        runCatching { app.unregisterReceiver(receiver) }
    }

    fun clearSlot(slot: Int) {
        _bySlot.update { old -> old - slot }
    }

    fun clearAll() {
        _bySlot.value = emptyMap()
    }
}

private fun parseLteCaJson(slot: Int, json: String): LteCaInfo {
    val root = JSONObject(json)
    val list = root.optJSONArray("list")

    val carriers = buildList {
        if (list != null) {
            for (i in 0 until list.length()) {
                val o = list.optJSONObject(i) ?: continue
                add(
                    LteCaCarrier(
                        idx = o.optInt("idx", -1),
                        pci = o.optInt("pci", -1),
                        dlEarfcn = o.optInt("dlEarfcn", -1),
                        ulEarfcn = o.optInt("ulEarfcn", -1),
                        band = o.optIntOrNull("band"),
                        bandText = o.optStringOrNull("bandText"),
                        dlBwRaw = o.optIntOrNull("dlBwRaw"),
                        dlBwText = o.optStringOrNull("dlBwText"),
                        scellState = o.optIntOrNull("scellState"),
                        scellStateText = o.optStringOrNull("scellStateText"),
                        ulEnabled = o.optIntOrNull("ulEnabled"),
                        rsrp = o.optIntOrNull("rsrp"),
                        rsrq = o.optIntOrNull("rsrq"),
                        sinr = o.optIntOrNull("sinr")
                    )
                )
            }
        }
    }

    return LteCaInfo(
        slot = slot,
        carriers = carriers,
        updatedAt = System.currentTimeMillis()
    )
}

private fun JSONObject.optIntOrNull(name: String): Int? {
    if (!has(name) || isNull(name)) return null
    return optInt(name)
}

private fun JSONObject.optStringOrNull(name: String): String? {
    if (!has(name) || isNull(name)) return null
    return optString(name).takeIf { it.isNotBlank() && it != "null" }
}
