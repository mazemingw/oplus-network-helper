package com.nvmex.networkhelper.xposed.utils.nrca

import android.util.Log
import java.lang.reflect.Array
import java.lang.reflect.Modifier

object HalRawDumper {

    private const val TAG = "HAL-RAW"

    fun dump(obj: Any?, prefix: String = "") {
        if (obj == null) {
            Log.d(TAG, "$prefix = null")
            return
        }

        val cls = obj.javaClass

        // 基础类型直接打
        if (cls.isPrimitive ||
            obj is String ||
            obj is Number ||
            obj is Boolean
        ) {
            Log.d(TAG, "$prefix = $obj")
            return
        }

        // 数组
        if (cls.isArray) {
            val len = Array.getLength(obj)
            Log.d(TAG, "$prefix [array size=$len]")
            for (i in 0 until len) {
                dump(Array.get(obj, i), "$prefix[$i]")
            }
            return
        }

        // 普通对象：反射字段
        Log.d(TAG, "$prefix {${cls.name}}")

        cls.declaredFields.forEach { field ->
            if (Modifier.isStatic(field.modifiers)) return@forEach
            field.isAccessible = true
            val v = runCatching { field.get(obj) }.getOrNull()
            dump(v, "$prefix.${field.name}")
        }
    }
}