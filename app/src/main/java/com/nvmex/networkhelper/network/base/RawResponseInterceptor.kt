package com.nvmex.networkhelper.network.base

import android.util.Log
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

//网络拦截器
class RawResponseInterceptor(
    private val tag: String = "NET_RAW"
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        val url = req.url.toString()

        // ✅ 总开关（你可以保留）
        val shouldLog = NetworkConfig.ENABLE_RAW_LOG

        if (!shouldLog) {
            return chain.proceed(req)
        }

        val start = System.nanoTime()

        // ---- Request log ----
        Log.i(tag, ">>> ${req.method} $url")
        Log.i(tag, ">>> headers:\n${req.headers}")

        // 若你想连 request body 都打（POST/JSON），可以加，但注意可能很长/有敏感信息
        // val reqBodyStr = req.body?.let { bodyToString(req) } ?: ""
        // Log.i(tag, ">>> requestBody=$reqBodyStr")

        val resp = chain.proceed(req)

        val tookMs = (System.nanoTime() - start) / 1_000_000.0
        Log.i(tag, "<<< code=${resp.code} (${String.format("%.1f", tookMs)}ms)")
        Log.i(tag, "<<< headers:\n${resp.headers}")

        val body = resp.body
        if (body == null) {
            Log.i(tag, "<<< body=(null)")
            return resp
        }

        // ---- Response body log (IMPORTANT: rebuild body) ----
        val contentType = body.contentType()
        val text = runCatching { body.string() }.getOrNull()

        Log.i(tag, "<<< body=${text ?: "(read body failed)"}")

        return if (text != null) {
            resp.newBuilder().body(text.toResponseBody(contentType)).build()
        } else {
            resp
        }
    }
}
