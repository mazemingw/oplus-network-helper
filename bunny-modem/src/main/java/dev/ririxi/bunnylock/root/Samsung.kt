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
        val getNrMode: Method,
        val setNrMode: Method,
    ) {
        fun call(
            method: Method,
            vararg args: Any?,
        ): Any? =
            try {
                method.invoke(service, *args)
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
    }

    /** Null on phones without the service, or where it lacks getNrMode(int) or setNrMode(int, int, boolean, String). */
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
            val getNrMode =
                methods.firstOrNull {
                    it.name == "getNrMode" && it.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType))
                }
            val setNrMode =
                methods.firstOrNull {
                    it.name == "setNrMode" &&
                        it.parameterTypes.contentEquals(
                            arrayOf(
                                Int::class.javaPrimitiveType,
                                Int::class.javaPrimitiveType,
                                Boolean::class.javaPrimitiveType,
                                String::class.java,
                            ),
                        )
                }
            if (getNrMode === null || setNrMode === null) null else SemTelephony(service, getNrMode, setNrMode)
        }.getOrNull()
    }

    fun nrModeAvailable(): Boolean = semTelephony !== null

    private fun requireSemTelephony(): SemTelephony =
        semTelephony ?: fail(R.string.samsung_no_nr_mode)

    fun nrMode(slot: Int): JSONObject {
        val telephony = requireSemTelephony()
        return JSONObject().put("mode", telephony.call(telephony.getNrMode, slot) as Int)
    }

    fun setNrMode(
        slot: Int,
        mode: Int,
    ): JSONObject {
        val telephony = requireSemTelephony()
        val callingPackage = if (Process.myUid() == 0) "root" else "com.android.shell"
        val accepted = telephony.call(telephony.setNrMode, slot, mode, false, callingPackage) as Boolean
        if (!accepted) fail(R.string.samsung_nr_mode_refused)
        return JSONObject().put("mode", mode)
    }
}
