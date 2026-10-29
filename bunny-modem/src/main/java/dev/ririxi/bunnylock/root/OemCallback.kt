package dev.ririxi.bunnylock.root

import android.os.Binder
import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Implements a vendor AIDL callback interface (e.g. Oplus' IOemHookCallback) at runtime and collects
 * the reply. Arguments are decoded from the parcel by the callback method's parameter types.
 */
class OemCallback(
    private val callbackInterface: Class<*>,
) {
    private val latch = CountDownLatch(1)

    @Volatile
    var reply: String = ""
        private set

    private val descriptor: String =
        runCatching { callbackInterface.getField("DESCRIPTOR").get(null) as String }.getOrDefault(callbackInterface.name)

    private val methodsByCode: Map<Int, Method> by lazy {
        val stub = runCatching { Class.forName($$"$${callbackInterface.name}$Stub", false, callbackInterface.classLoader) }.getOrNull()
        callbackInterface.methods
            .mapNotNull { method ->
                val code = stub?.let { transactionCode(it, method) }
                code?.let { it to method }
            }.toMap()
    }

    /** AIDL gives the Stub a TRANSACTION_<method> constant for every method of the interface. */
    private fun transactionCode(
        stub: Class<*>,
        method: Method,
    ): Int? =
        runCatching {
            stub.getDeclaredField("TRANSACTION_${method.name}").apply { isAccessible = true }.getInt(null)
        }.getOrNull()

    private val binder =
        object : Binder() {
            override fun onTransact(
                code: Int,
                data: Parcel,
                reply: Parcel?,
                flags: Int,
            ): Boolean {
                if (code !in FIRST_CALL_TRANSACTION..LAST_CALL_TRANSACTION) return super.onTransact(code, data, reply, flags)
                // Reads past the interface token. A descriptor that doesn't match is no reason to drop the reply.
                runCatching { data.enforceInterface(descriptor) }
                val method = methodsByCode[code]
                this@OemCallback.reply = if (method === null) "" else readText(method.parameterTypes, data)
                reply?.writeNoException()
                latch.countDown()
                return true
            }
        }

    /** The text arguments joined by newlines. Numbers are only read past, and reading stops at a type whose size in the parcel isn't known. */
    private fun readText(
        parameterTypes: Array<Class<*>>,
        data: Parcel,
    ): String {
        val parts = ArrayList<String>()
        for (type in parameterTypes) {
            when (type) {
                Int::class.javaPrimitiveType -> data.readInt()
                Long::class.javaPrimitiveType -> data.readLong()
                Boolean::class.javaPrimitiveType -> data.readInt()
                String::class.java -> data.readString()?.let { parts += it }
                ByteArray::class.java -> data.createByteArray()?.let { parts += String(it, Charsets.ISO_8859_1) }
                Array<String>::class.java -> data.createStringArray()?.let { parts += it.joinToString("\n") }
                else -> return parts.joinToString("\n")
            }
        }
        return parts.joinToString("\n")
    }

    /** What to pass as the callback argument. The generated AIDL client only asks it for asBinder(), to send [binder] across. */
    val proxy: Any =
        Proxy.newProxyInstance(callbackInterface.classLoader, arrayOf(callbackInterface)) { _, method, _ ->
            when (method.name) {
                "asBinder" -> binder
                "hashCode" -> System.identityHashCode(binder)
                "equals" -> false
                "toString" -> "BunnyOemCallback"
                else -> null
            }
        }

    fun await(timeoutMs: Long): Boolean = latch.await(timeoutMs, TimeUnit.MILLISECONDS)

    companion object {
        /** An AIDL interface, as opposed to a plain IBinder parameter. */
        fun isCallback(type: Class<*>): Boolean =
            type.isInterface && !IBinder::class.java.isAssignableFrom(type) && IInterface::class.java.isAssignableFrom(type)
    }
}
