package com.nvmex.networkhelper.util.telephony

import android.content.Context
import dev.qcom.efs.platform.RootPhone
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Android's USER allowed-network-types setting only; no QMI/DIAG/EFS operations. */
object FiveGNetwork {
    private val mutex = Mutex()
    suspend fun read(context: Context): Boolean = mutex.withLock {
        RootPhone.read(context, "read5g").getBoolean("enabled")
    }
    suspend fun set(context: Context, allowed: Boolean): Boolean = mutex.withLock {
        RootPhone.read(context, "set5g", allowed).getBoolean("enabled")
    }
    suspend fun toggle(context: Context): Boolean = mutex.withLock {
        RootPhone.read(context, "toggle5g").getBoolean("enabled")
    }
}
