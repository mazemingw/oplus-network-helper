package com.nvmex.networkhelper.util.qmi

import android.content.Context
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Hardware capabilities are shared across SIMs; no preferences or cell locks are written to read them. */
object QmiHardwareCapabilities {
    @Volatile private var cached: Map<QmiBand, Set<Int>>? = null
    private val loadLock = Mutex()

    fun peek(): Map<QmiBand, Set<Int>>? = cached
    fun record(hardware: Map<QmiBand, Set<Int>>?) {
        if (hardware != null) cached = hardware.mapValues { it.value.toSet() }
    }

    suspend fun load(context: Context): Map<QmiBand, Set<Int>> = loadLock.withLock {
        cached?.let { return@withLock it }
        val client = QmiModemClient(context)
        try { client.readHardware() }
        finally { withContext(NonCancellable) { client.close() } }
    }
}
