package dev.ririxi.bunnylock.root

import android.os.IBinder
import android.os.Process
import dev.ririxi.bunnylock.modem.R
import org.json.JSONObject
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * Samsung's ISemTelephony ("isemtelephony"): 5G mode on One UI without root.
 * Modes (TelephonyManager.SEM_NR_MODE_*): 0 SA + NSA, 1 NSA only, 2 SA only. Samsung's IMS constants have 0 and 2 swapped.
 */
object Samsung {
    private class SemTelephony(
        private val service: Any,
        private val nrModeGetter: Method,
        private val nrModeSetter: Method,
    ) {
        val perSlot = nrModeGetter.parameterCount == 1

        fun nrMode(slot: Int): Int = (if (perSlot) call(nrModeGetter, slot) else call(nrModeGetter)) as Int

        fun setNrMode(
            slot: Int,
            mode: Int,
            callingPackage: String,
        ): Boolean = (if (perSlot) call(nrModeSetter, slot, mode, false, callingPackage) else call(nrModeSetter, mode)) as Boolean

        private fun call(
            method: Method,
            vararg args: Any?,
        ): Any? =
            try {
                method.invoke(service, *args)
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
    }

    private fun Array<Method>.method(
        name: String,
        vararg parameterTypes: Class<*>?,
    ): Method? = firstOrNull { it.name == name && it.parameterTypes.contentEquals(parameterTypes) }

    private val semTelephony: SemTelephony? by lazy {
        runCatching {
            val binder =
                Class
                    .forName("android.os.ServiceManager")
                    .getMethod("getService", String::class.java)
                    .invoke(null, "isemtelephony") as? IBinder ?: return@runCatching null
            val service =
                Class
                    .forName($$"com.android.internal.telephony.ISemTelephony$Stub")
                    .getMethod("asInterface", IBinder::class.java)
                    .invoke(null, binder) ?: return@runCatching null
            val methods = service.javaClass.methods
            val intType = Int::class.javaPrimitiveType
            val booleanType = Boolean::class.javaPrimitiveType
            listOf(
                methods.method("getNrMode", intType) to methods.method("setNrMode", intType, intType, booleanType, String::class.java),
                methods.method("getNrMode") to methods.method("setNrMode", intType),
            ).firstNotNullOfOrNull { (getter, setter) ->
                if (getter === null || setter === null) null else SemTelephony(service, getter, setter)
            }
        }.getOrNull()
    }

    fun nrModeAvailable(): Boolean = semTelephony !== null

    private fun requireSemTelephony(
        subId: Int,
        dataSubId: Int,
    ): SemTelephony {
        val telephony = semTelephony ?: fail(R.string.samsung_no_nr_mode)
        if (!telephony.perSlot && subId != dataSubId) fail(R.string.samsung_nr_mode_data_sim_only)
        return telephony
    }

    fun nrMode(
        slot: Int,
        subId: Int,
        dataSubId: Int,
    ): JSONObject = JSONObject().put("mode", requireSemTelephony(subId, dataSubId).nrMode(slot))

    fun setNrMode(
        slot: Int,
        subId: Int,
        dataSubId: Int,
        mode: Int,
    ): JSONObject {
        val callingPackage = if (Process.myUid() == 0) "root" else "com.android.shell"
        if (!requireSemTelephony(subId, dataSubId).setNrMode(slot, mode, callingPackage)) fail(R.string.samsung_nr_mode_refused)
        return JSONObject().put("mode", mode)
    }
}
