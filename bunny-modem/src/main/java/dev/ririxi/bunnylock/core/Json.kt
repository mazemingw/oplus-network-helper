package dev.ririxi.bunnylock.core

import org.json.JSONArray
import org.json.JSONObject

fun JSONArray.toIntList(): List<Int> = List(length()) { getInt(it) }

fun JSONArray.objects(): List<JSONObject> = List(length()) { getJSONObject(it) }

fun JSONObject.intList(key: String): List<Int>? = optJSONArray(key)?.toIntList()
