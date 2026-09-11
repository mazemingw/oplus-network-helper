package com.nvmex.networkhelper.xposed.broad

import android.content.Context
import android.content.Intent
import com.nvmex.networkhelper.model.network.GlobalQosEvent
import com.nvmex.networkhelper.model.network.QosData
import com.nvmex.networkhelper.xposed.config.Config
import com.nvmex.networkhelper.xposed.logger.Logger
import de.robv.android.xposed.XposedHelpers
import java.util.concurrent.atomic.AtomicReference

class BroadcastHelper {
    private val appCtxRef = AtomicReference<Context?>()

    fun setAppContext(ctx: Context) {
        appCtxRef.set(ctx.applicationContext ?: ctx)
    }

    private fun getAppContext(): Context? {
        var ctx = appCtxRef.get()
        if (ctx != null) return ctx

        try {
            val activityThread = XposedHelpers.callStaticMethod(
                XposedHelpers.findClass("android.app.ActivityThread", null),
                "currentApplication"
            ) as? android.app.Application

            ctx = activityThread?.applicationContext
            if (ctx != null) {
                appCtxRef.set(ctx)
                Logger.log("[BroadcastHelper] acquired Context: $ctx")
            } else {
                Logger.log("[BroadcastHelper] failed to acquire Context by reflection")
            }
        } catch (t: Throwable) {
            Logger.logE("[BroadcastHelper] acquire Context failed", t)
        }
        return ctx
    }

    fun emitNrcaToApp(ts: Long, slot: Int, type: Int, carriers: Int, json: String) {
        val ctx = getAppContext() ?: run {
            Logger.log("[BroadcastHelper] no Context, NRCA broadcast skipped")
            return
        }
        try {
            val intent = Intent(Config.ACTION_NRCA_UPDATE).apply {
                setPackage(Config.TARGET_PKG)
                putExtra(Config.EXTRA_TS, ts)
                putExtra(Config.EXTRA_SLOT, slot)
                putExtra(Config.EXTRA_TYPE, type)
                putExtra(Config.EXTRA_CARRIERS, carriers)
                putExtra(Config.EXTRA_JSON, json)
            }
            ctx.sendBroadcast(intent)
        } catch (t: Throwable) {
            Logger.logE("[BroadcastHelper] emitNrcaToApp failed", t)
        }
    }

    fun emitLteCaToApp(ts: Long, slot: Int, carriers: Int, json: String) {
        val ctx = getAppContext() ?: run {
            Logger.log("[BroadcastHelper] no Context, LTECA broadcast skipped")
            return
        }
        try {
            val intent = Intent(Config.ACTION_LTECA_UPDATE).apply {
                setPackage(Config.TARGET_PKG)
                putExtra(Config.EXTRA_TS, ts)
                putExtra(Config.EXTRA_SLOT, slot)
                putExtra(Config.EXTRA_CARRIERS, carriers)
                putExtra(Config.EXTRA_JSON, json)
            }
            ctx.sendBroadcast(intent)
        } catch (t: Throwable) {
            Logger.logE("[BroadcastHelper] emitLteCaToApp failed", t)
        }
    }

    /**
     * 单卡实时 QoS 快照：严格按 subId 分桶
     */
    fun emitQosToApp(qosData: QosData) {
        val ctx = getAppContext() ?: run {
            Logger.log("[BroadcastHelper] no Context, QoS broadcast skipped")
            return
        }
        try {
            val json = qosData.toJson()
            val intent = Intent(Config.ACTION_QOS_UPDATE).apply {
                setPackage(Config.TARGET_PKG)

                putExtra(Config.EXTRA_QOS_JSON, json)

                // 顶层主键 / 关键字段
                putExtra(Config.EXTRA_QOS_SUB_ID, qosData.subId)
                putExtra(Config.EXTRA_QOS_RAT, qosData.rat)
                putExtra(Config.EXTRA_QOS_CELL_ID, qosData.cellId)
                putExtra(Config.EXTRA_QOS_PCI, qosData.pci)
                putExtra(Config.EXTRA_QOS_ARFCN, qosData.arfcn)
                putExtra(Config.EXTRA_QOS_BAND, qosData.band)
                putExtra(Config.EXTRA_QOS_TS, qosData.timestamp)
            }
            ctx.sendBroadcast(intent)

            Logger.log(
                "[BroadcastHelper] QoS broadcast sent " +
                    "subId=${qosData.subId}, rat=${qosData.rat}, " +
                    "cellId=${qosData.cellId}, pci=${qosData.pci}, " +
                    "arfcn=${qosData.arfcn}, jsonLength=${json.length}"
            )
        } catch (t: Throwable) {
            Logger.logE("[BroadcastHelper] emitQosToApp failed", t)
        }
    }

    /**
     * 全局事件：不归属单卡
     */
    fun emitGlobalQosEventToApp(event: GlobalQosEvent) {
        val ctx = getAppContext() ?: run {
            Logger.log("[BroadcastHelper] no Context, global QoS event broadcast skipped")
            return
        }
        try {
            val json = event.toJson()
            val intent = Intent(Config.ACTION_QOS_GLOBAL_EVENT_UPDATE).apply {
                setPackage(Config.TARGET_PKG)
                putExtra(Config.EXTRA_QOS_GLOBAL_JSON, json)
                putExtra(Config.EXTRA_QOS_TS, event.timestamp)
            }
            ctx.sendBroadcast(intent)
            Logger.log("[BroadcastHelper] global QoS event broadcast sent, jsonLength=${json.length}")
        } catch (t: Throwable) {
            Logger.logE("[BroadcastHelper] emitGlobalQosEventToApp failed", t)
        }
    }
}
