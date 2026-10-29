package dev.ririxi.bunnylock.modem

import android.content.Context
import androidx.annotation.StringRes
import org.json.JSONArray
import org.json.JSONObject

class Text(
    @StringRes val id: Int,
    vararg val args: Any,
)

fun Context.string(text: Text): String = getString(text.id, *text.args.map { resolve(it) }.toTypedArray())

private fun Context.resolve(arg: Any): Any =
    when (arg) {
        is Text -> string(arg)
        is List<*> -> andList(arg.filterNotNull().map { resolve(it).toString() })
        else -> arg
    }

fun verbatim(text: String) = Text(R.string.verbatim, text)

// The root helper runs from the same APK, so the ids it sends match the app's.
fun Text.toJson(): JSONObject = JSONObject().put("id", id).put("args", JSONArray().apply { args.forEach { put(toJsonValue(it)) } })

private fun toJsonValue(arg: Any): Any =
    when (arg) {
        is Text -> arg.toJson()
        is List<*> -> JSONArray().apply { arg.filterNotNull().forEach { put(toJsonValue(it)) } }
        else -> arg
    }

fun JSONObject.toText(): Text {
    val args = optJSONArray("args") ?: JSONArray()
    return Text(getInt("id"), *Array(args.length()) { fromJsonValue(args.get(it)) })
}

private fun fromJsonValue(value: Any): Any =
    when (value) {
        is JSONObject -> value.toText()
        is JSONArray -> List(value.length()) { fromJsonValue(value.get(it)) }
        else -> value
    }

fun JSONObject.optText(key: String): Text? = textOrNull(opt(key))

fun JSONObject.optTexts(key: String): List<Text> {
    val array = optJSONArray(key) ?: return emptyList()
    return (0 until array.length()).mapNotNull { textOrNull(array.get(it)) }
}

private fun textOrNull(value: Any?): Text? =
    when (value) {
        is JSONObject -> value.toText()
        is String -> value.takeIf { it.isNotBlank() }?.let(::verbatim)
        else -> null
    }
