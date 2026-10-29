package dev.ririxi.bunnylock.shizuku

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import dev.ririxi.bunnylock.root.RootServer
import kotlin.system.exitProcess

/**
 * Shizuku UserService: runs as shell (ADB) or root, depending on how Shizuku was started.
 * Hand-written binder instead of AIDL so the build needs no Java compilation.
 */
class ShizukuService : Binder() {
    init {
        attachInterface(null, DESCRIPTOR)
    }

    override fun onTransact(
        code: Int,
        data: Parcel,
        reply: Parcel?,
        flags: Int,
    ): Boolean =
        when (code) {
            TRANSACTION_DESTROY -> {
                exitProcess(0)
            }

            TRANSACTION_CALL -> {
                data.enforceInterface(DESCRIPTOR)
                val response = RootServer.respond(data.readString().orEmpty())
                reply?.writeNoException()
                reply?.writeString(response)
                true
            }

            else -> {
                super.onTransact(code, data, reply, flags)
            }
        }

    class Proxy(
        private val remote: IBinder,
    ) {
        val alive: Boolean get() = remote.pingBinder()

        fun call(request: String): String {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(DESCRIPTOR)
                data.writeString(request)
                remote.transact(TRANSACTION_CALL, data, reply, 0)
                reply.readException()
                return reply.readString().orEmpty()
            } finally {
                data.recycle()
                reply.recycle()
            }
        }
    }

    companion object {
        private const val DESCRIPTOR = "dev.ririxi.bunnylock.shizuku.ShizukuService"
        private const val TRANSACTION_CALL = IBinder.FIRST_CALL_TRANSACTION

        // Shizuku stops a UserService through AIDL method id 16777114, which goes over the wire offset like any other.
        private const val TRANSACTION_DESTROY = IBinder.FIRST_CALL_TRANSACTION + 16777114
    }
}
