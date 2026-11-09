package com.nvmex.networkhelper.util.diagnostics

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/** Request paths only: never record query strings, headers, request bodies or response bodies. */
class DiagnosticNetworkInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val context = mapOf("method" to request.method, "path" to request.url.encodedPath)
        return try {
            chain.proceed(request).also { response ->
                if (response.code >= 500) DiagnosticReporter.report("http", "HTTP ${response.code}", context)
            }
        } catch (error: IOException) {
            DiagnosticReporter.report("http", error, context)
            throw error
        }
    }
}
