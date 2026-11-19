package dev.ririxi.bunnylock.root

import android.os.IBinder
import android.os.Parcel

// Unisoc's services that take AT commands. Their AIDL classes aren't on any class path the helper can load, so the transactions are written by hand.
internal class UnisocAtService(
    val name: String,
    val label: String,
    private val transaction: Int,
    private val writeRequest: Parcel.(phoneId: Int, command: String) -> Unit,
    private val readReply: Parcel.() -> String,
) {
    fun send(
        binder: IBinder,
        slot: Int,
        command: String,
    ): String {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(binder.interfaceDescriptor ?: error("$name has no interface descriptor"))
            data.writeRequest(slot, command)
            check(binder.transact(transaction, data, reply, 0)) { "$name has no transaction $transaction" }
            reply.readException()
            return reply.readReply()
        } finally {
            data.recycle()
            reply.recycle()
        }
    }
}

internal val UNISOC_AT_SERVICES =
    listOf(
        UnisocAtService(
            name = "irit",
            label = "irit.sendAtCmd",
            transaction = 2,
            writeRequest = { phoneId, command ->
                writeString(command)
                writeReplyArray(phoneId)
                writeInt(phoneId)
            },
            readReply = {
                val result = readInt()
                val lines = createStringArray()?.filterNotNull()?.filter { it.isNotBlank() }.orEmpty()
                if (lines.isEmpty() && result < 0) "ERROR" else lines.joinToString("\n")
            },
        ),
        UnisocAtService(
            name = "vendor.sprd.hardware.tool.IToolControl/default",
            label = "IToolControl #3",
            transaction = 3,
            writeRequest = { phoneId, command ->
                writeInt(phoneId)
                writeString(command)
            },
            readReply = { readString().orEmpty() },
        ),
        UnisocAtService(
            name = "vendor.sprd.hardware.log.ILogControl/default",
            label = "ILogControl #1",
            transaction = 1,
            writeRequest = { phoneId, command ->
                writeString("miscserver")
                writeString("sendAt $phoneId $command")
            },
            readReply = { readString().orEmpty() },
        ),
    )

// The reply array of IRadioInteractor.sendAtCmd may be declared out or inout, and the two read the call differently.
// An out array is read as its length alone, so the phone ID is taken from the next int, which is the placeholder's length.
// An inout array is read whole, placeholder and all, and the phone ID after it. A placeholder as long as the phone ID suits both.
private fun Parcel.writeReplyArray(phoneId: Int) {
    writeInt(1)
    writeString("x".repeat(phoneId))
}
