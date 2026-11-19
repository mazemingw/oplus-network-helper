package dev.ririxi.bunnylock.modem

import org.json.JSONObject

enum class ImsFeature(
    val key: String,
    val label: String,
) {
    VOLTE("volte", "VoLTE"),
    VOWIFI("vowifi", "VoWiFi"),
}

enum class ImsBlock {
    NOT_OFFERED,
    LOCKED,
}

sealed interface ImsSetting {
    data class Readable(
        val enabled: Boolean,
        val blocked: ImsBlock?,
    ) : ImsSetting

    data class Unreadable(
        val error: String,
    ) : ImsSetting

    companion object {
        internal fun from(json: JSONObject): ImsSetting {
            if (!json.has("enabled")) return Unreadable(json.optString("error"))
            val blocked =
                when (json.optString("blocked")) {
                    "notOffered" -> ImsBlock.NOT_OFFERED
                    "locked" -> ImsBlock.LOCKED
                    else -> null
                }
            return Readable(json.getBoolean("enabled"), blocked)
        }
    }
}

internal fun imsSettingsFrom(json: JSONObject): Map<ImsFeature, ImsSetting> =
    ImsFeature.entries.mapNotNull { feature -> json.optJSONObject(feature.key)?.let { feature to ImsSetting.from(it) } }.toMap()
