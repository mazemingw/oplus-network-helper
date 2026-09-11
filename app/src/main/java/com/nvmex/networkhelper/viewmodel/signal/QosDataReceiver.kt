package com.nvmex.networkhelper.viewmodel.signal

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.nvmex.networkhelper.model.network.GlobalQosEvent
import com.nvmex.networkhelper.model.network.QosData
import com.nvmex.networkhelper.xposed.config.Config

class QosDataReceiver : BroadcastReceiver() {

    private var viewModel: QosViewModel? = null
    private var registered = false

    fun attachViewModel(viewModel: QosViewModel) {
        this.viewModel = viewModel
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Config.ACTION_QOS_UPDATE -> handleQosUpdate(intent)
            Config.ACTION_QOS_GLOBAL_EVENT_UPDATE -> handleGlobalEventUpdate(intent)
        }
    }

    private fun handleQosUpdate(intent: Intent) {
        val subId = intent.getIntExtra(Config.EXTRA_QOS_SUB_ID, -1)
        val qosJson = intent.getStringExtra(Config.EXTRA_QOS_JSON) ?: return
        if (subId == -1) return

        runCatching {
            val qosData = QosData.fromJson(qosJson)
            viewModel?.updateQosData(subId, qosData)
        }
    }

    private fun handleGlobalEventUpdate(intent: Intent) {
        val json = intent.getStringExtra(Config.EXTRA_QOS_GLOBAL_JSON) ?: return

        runCatching {
            val event = GlobalQosEvent.fromJson(json)
            viewModel?.updateGlobalEvent(event)
        }
    }

    fun register(context: Context, viewModel: QosViewModel) {
        if (registered) return

        attachViewModel(viewModel)

        val filter = IntentFilter().apply {
            addAction(Config.ACTION_QOS_UPDATE)
            addAction(Config.ACTION_QOS_GLOBAL_EVENT_UPDATE)
        }

        context.registerReceiver(this, filter, Context.RECEIVER_EXPORTED)
        registered = true
    }

    fun unregister(context: Context) {
        if (!registered) return
        runCatching { context.unregisterReceiver(this) }
        registered = false
    }
}