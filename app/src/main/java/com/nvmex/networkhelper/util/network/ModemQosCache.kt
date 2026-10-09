package com.nvmex.networkhelper.util.network

import android.content.Context
import android.telephony.SubscriptionManager
import com.nvmex.networkhelper.model.network.ModemQosInfo
import com.nvmex.networkhelper.model.network.ModemQosSessions
import com.nvmex.networkhelper.util.shell.SuShellRunner
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64

class ModemQosCache(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val su = SuShellRunner()

    fun restoreActive(): Map<Int, ModemQosSessions> {
        val subscriptions = activeSubscriptions()
        val iccidBySlot = resolveIccidsBySlot(subscriptions)
        return subscriptions.mapNotNull { subscription ->
            val iccid = iccidBySlot[subscription.simSlotIndex] ?: return@mapNotNull null
            val encoded = prefs.getString(ModemQosCacheCodec.keyForIccid(iccid), null)
                ?: return@mapNotNull null
            ModemQosCacheCodec.decode(encoded, subscription.subscriptionId, subscription.simSlotIndex)
                ?.let { subscription.subscriptionId to it }
        }.toMap()
    }

    fun save(subId: Int, sessions: ModemQosSessions): Boolean {
        val subscriptions = activeSubscriptions()
        val subscription = subscriptions.firstOrNull { it.subscriptionId == subId } ?: return false
        val iccid = resolveIccidsBySlot(subscriptions)[subscription.simSlotIndex] ?: return false
        val encoded = ModemQosCacheCodec.encode(sessions) ?: return false
        return prefs.edit().putString(ModemQosCacheCodec.keyForIccid(iccid), encoded).commit()
    }

    private fun resolveIccidsBySlot(subscriptions: List<android.telephony.SubscriptionInfo>): Map<Int, String> {
        val publicValues = subscriptions.mapNotNull { subscription ->
            val iccid = runCatching { subscription.iccId }.getOrNull()
                ?.takeIf(ModemQosIccidParser::isUsable)
            iccid?.let { subscription.simSlotIndex to it }
        }.toMap()
        if (publicValues.size == subscriptions.size) return publicValues

        val result = su.execSu(
            "content query --uri content://telephony/siminfo --projection _id:icc_id:sim_id",
            4_000
        )
        if (result.code != 0) return publicValues
        return ModemQosIccidParser.parseBySlot(result.out) + publicValues
    }

    private fun activeSubscriptions() = runCatching {
        appContext.getSystemService(SubscriptionManager::class.java)?.activeSubscriptionInfoList.orEmpty()
    }.getOrDefault(emptyList())

    private companion object {
        const val PREFS_NAME = "modem_qos_iccid_cache_v1"
    }
}

internal object ModemQosIccidParser {
    private val iccidPattern = Regex("(?:^|,\\s*)icc_id=([^,\\s]+)")
    private val slotPattern = Regex("(?:^|,\\s*)sim_id=(-?\\d+)")

    fun isUsable(value: String): Boolean {
        val normalized = value.trim()
        return normalized.length >= 10 &&
            normalized.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' } &&
            normalized.any { it != '0' && it != 'F' && it != 'f' }
    }

    fun parseBySlot(output: String): Map<Int, String> = output.lineSequence().mapNotNull { line ->
        val iccid = iccidPattern.find(line)?.groupValues?.getOrNull(1)?.takeIf(::isUsable)
            ?: return@mapNotNull null
        val slot = slotPattern.find(line)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?.takeIf { it >= 0 } ?: return@mapNotNull null
        slot to iccid
    }.toMap()
}

internal object ModemQosCacheCodec {
    private const val VERSION = "1"
    private const val NULL = "~"

    fun keyForIccid(iccid: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(iccid.trim().toByteArray(StandardCharsets.UTF_8))
        return "iccid_" + digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    fun encode(sessions: ModemQosSessions): String? {
        val infos = listOfNotNull(sessions.lteIms, sessions.nrIms, sessions.lteData, sessions.nrData)
        if (infos.isEmpty()) return null
        return buildString {
            appendLine(VERSION)
            infos.forEach { info ->
                append(
                    listOf(
                        info.source.name,
                        info.sessionKind.name,
                        encodeText(info.dnn),
                        encodeNumber(info.logCode),
                        encodeNumber(info.qci),
                        encodeNumber(info.fiveQi),
                        encodeNumber(info.qfi),
                        encodeNumber(info.downlinkAmbrKbps),
                        encodeNumber(info.uplinkAmbrKbps),
                        info.updatedAt.toString()
                    ).joinToString("|")
                )
                append('\n')
            }
        }
    }

    fun decode(encoded: String, subId: Int, slotIndex: Int?): ModemQosSessions? = runCatching {
        val lines = encoded.lineSequence().filter(String::isNotBlank).toList()
        require(lines.firstOrNull() == VERSION)
        var sessions = ModemQosSessions()
        lines.drop(1).forEach { line ->
            val values = line.split('|')
            require(values.size == 10)
            val info = ModemQosInfo(
                subId = subId,
                slotIndex = slotIndex,
                source = ModemQosInfo.Source.valueOf(values[0]),
                sessionKind = ModemQosInfo.SessionKind.valueOf(values[1]),
                dnn = decodeText(values[2]),
                logCode = values[3].nullableInt(),
                qci = values[4].nullableInt(),
                fiveQi = values[5].nullableInt(),
                qfi = values[6].nullableInt(),
                downlinkAmbrKbps = values[7].nullableLong(),
                uplinkAmbrKbps = values[8].nullableLong(),
                updatedAt = values[9].toLong(),
                cached = true
            )
            sessions = sessions.withInfo(info)
        }
        require(listOf(sessions.lteIms, sessions.nrIms, sessions.lteData, sessions.nrData).any { it != null })
        sessions
    }.getOrNull()

    private fun encodeText(value: String?): String = value?.let {
        Base64.getUrlEncoder().withoutPadding().encodeToString(it.toByteArray(StandardCharsets.UTF_8))
    } ?: NULL

    private fun decodeText(value: String): String? = if (value == NULL) null else {
        String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)
    }

    private fun encodeNumber(value: Number?): String = value?.toString() ?: NULL
    private fun String.nullableInt(): Int? = if (this == NULL) null else toInt()
    private fun String.nullableLong(): Long? = if (this == NULL) null else toLong()
}
